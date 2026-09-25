from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from sqlalchemy.dialects import postgresql

from app.core.checkpoint import store as module
from app.core.common.settings import settings


@pytest.fixture
def sql_store():
    store = module.CheckpointStore(database_url="postgresql://test.invalid/runtime")
    session = SimpleNamespace(execute=AsyncMock(), scalar=AsyncMock(), scalars=AsyncMock())

    @asynccontextmanager
    async def opened():
        yield session

    class Sessions:
        begin = staticmethod(opened)

        def __call__(self):
            return opened()

    store._sessions = Sessions()
    return store, session


def compiled(statement):
    return statement.compile(dialect=postgresql.dialect())


@pytest.mark.asyncio
async def test_session_factory_preserves_driver_dsn_and_closes_engine(monkeypatch):
    engine = SimpleNamespace(dispose=AsyncMock())
    create = Mock(return_value=engine)
    factory = Mock(return_value=object())
    connect = AsyncMock(return_value="connection")
    monkeypatch.setattr(module, "create_async_engine", create)
    monkeypatch.setattr(module, "async_sessionmaker", factory)
    monkeypatch.setattr(module.asyncpg, "connect", connect)
    dsn = "postgresql://test.invalid/runtime?sslmode=require"
    store = module.CheckpointStore(database_url=dsn)
    first = await store._get_sessions()
    assert await store._get_sessions() is first
    create.assert_called_once()
    assert create.call_args.kwargs["max_overflow"] == 0
    assert await create.call_args.kwargs["async_creator"]() == "connection"
    connect.assert_awaited_once_with(dsn, command_timeout=10)
    await store.close()
    engine.dispose.assert_awaited_once()
    assert store._engine is store._sessions is None


@pytest.mark.asyncio
@pytest.mark.parametrize("limit", [0, 2])
async def test_save_uses_atomic_bounded_insert_and_redacts_payload(sql_store, monkeypatch, limit):
    store, session = sql_store
    monkeypatch.setattr(settings.config.checkpoint, "max_per_session", limit)
    await store.save(
        "session",
        "run",
        "plan",
        {
            "metadata": {"tenant_id": "tenant", "user_id": "user"},
            "context_summary": "not json",
            "password": "synthetic",
        },
    )
    statement = session.execute.call_args.args[0]
    sql = compiled(statement)
    insert_statement = statement.get_final_froms()[0].element if limit else statement
    values = compiled(insert_statement).params
    assert values["tenant_id"] == "tenant"
    assert values["user_id"] == "user"
    assert values["payload_json"]["state"]["password"] == "[REDACTED]"
    assert "context_summary" not in values["payload_json"]["state"]
    assert ("DELETE FROM" in str(sql)) == (limit > 0)
    assert "INSERT INTO" in str(sql)


@pytest.mark.asyncio
async def test_reads_and_resume_claims_include_requested_owner_and_limit(sql_store):
    store, session = sql_store
    payload = {"session_id": "session", "run_id": "run", "stage": "plan", "state": {"private": "body"}}
    session.scalar.return_value = payload
    assert await store.load_latest("session") == payload
    assert compiled(session.scalar.call_args.args[0]).params["session_id_1"] == "session"
    assert await store.load_latest_by_run("session", "run", "tenant", "user") == payload
    sql = compiled(session.scalar.call_args.args[0])
    assert sql.params["tenant_id_1"] == "tenant"
    assert sql.params["user_id_1"] == "user"
    assert await store.load_latest_by_run_internal("session", "run") == payload
    assert "tenant_id" not in str(compiled(session.scalar.call_args.args[0]))
    session.scalar.return_value = 1
    assert await store.claim_resume("session", "run", "next", "tenant", "user")
    sql = compiled(session.scalar.call_args.args[0])
    assert "ON CONFLICT" in str(sql)
    assert sql.params["source_run_id"] == "run" and sql.params["resumed_run_id"] == "next"
    session.scalar.return_value = None
    assert not await store.claim_resume("session", "run", "duplicate", "tenant", "user")
    session.scalars.return_value = SimpleNamespace(all=lambda: [payload])
    summaries = await store.list_snapshots("session", "tenant", "user")
    assert summaries == [
        {"session_id": "session", "run_id": "run", "stage": "plan", "saved_at": None, "storage": "postgresql"}
    ]
    sql = compiled(session.scalars.call_args.args[0])
    assert sql.params["tenant_id_1"] == "tenant" and sql.params["user_id_1"] == "user"


@pytest.mark.asyncio
async def test_disabled_checkpoint_and_unlimited_memory_retention(monkeypatch):
    store = module.CheckpointStore(database_url="")
    monkeypatch.setattr(settings.config.checkpoint, "enabled", False)
    await store.save("session", "run", "plan", {})
    assert await store.load_latest("session") is None
    monkeypatch.setattr(settings.config.checkpoint, "enabled", True)
    monkeypatch.setattr(settings.config.checkpoint, "max_per_session", 0)
    for index in range(3):
        await store.save("session", str(index), "plan", {"context_summary": "[]"})
    assert len(store._memory) == 3
    assert "context_summary" not in (await store.load_latest("session"))["state"]
