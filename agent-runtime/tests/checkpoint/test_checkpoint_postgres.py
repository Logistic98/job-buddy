"""通过 CHECKPOINT_TEST_DATABASE_URL 在隔离 schema 中验证真实 PostgreSQL ORM 行为。"""

import asyncio
import os
from pathlib import Path
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlalchemy.schema import CreateSchema, DropSchema

from app.core.checkpoint.entities import RunCheckpoint
from app.core.checkpoint.store import CheckpointStore
from app.core.common.settings import settings


@pytest_asyncio.fixture
async def postgres_store(monkeypatch):
    url = os.getenv("CHECKPOINT_TEST_DATABASE_URL")
    if not url:
        pytest.skip("CHECKPOINT_TEST_DATABASE_URL is required for PostgreSQL integration")
    store = CheckpointStore(database_url=url)
    await store._get_sessions()
    engine = store._engine
    schema = "checkpoint_test_" + uuid4().hex
    async with engine.begin() as connection:
        await connection.execute(CreateSchema(schema))
    scoped_engine = engine.execution_options(schema_translate_map={None: schema})
    store._sessions = async_sessionmaker(scoped_engine, expire_on_commit=False)
    try:
        async with scoped_engine.begin() as connection:
            await connection.execute(select(func.set_config("search_path", schema, True)))
            migrations = Path(__file__).resolve().parents[3] / "agent-backend/src/main/resources/db/migration"
            # 使用正式迁移的检查点定义，避免 ORM 元数据建表掩盖类型或列名不匹配。
            initial = (migrations / "V1_0_2__Create_chat_and_agent_schema.sql").read_text().split(";", 1)[0]
            await connection.exec_driver_sql(initial)
            recovery = (migrations / "V1_0_17__Add_agent_run_checkpoint_recovery_scope.sql").read_text()
            for statement in recovery.split(";"):
                if statement.strip():
                    await connection.exec_driver_sql(statement)
        monkeypatch.setattr(settings.config.checkpoint, "enabled", True)
        monkeypatch.setattr(settings.config.checkpoint, "max_per_session", 2)
        yield store
    finally:
        async with engine.begin() as connection:
            await connection.execute(DropSchema(schema, cascade=True))
        await store.close()


async def test_persistence_retention_owner_filter_and_atomic_claim(postgres_store, monkeypatch):
    store = postgres_store
    # 引号和 SQL 片段应作为普通数据绑定，不能改变过滤条件。
    session_id = "session'; DELETE FROM agent_run_checkpoint; --"
    state = {"metadata": {"tenant_id": "tenant-a", "user_id": "user-a"}, "turn": 1}
    for turn in range(4):
        await store.save(session_id, f"run-{turn}", "observe", {**state, "turn": turn})
    await store.save("other-session", "other-run", "observe", state)
    assert (await store.load_latest(session_id))["state"]["turn"] == 3
    assert await store.load_latest_by_run(session_id, "run-3", "tenant-b", "user-a") is None
    assert await store.load_latest_by_run(session_id, "run-3", "tenant-a", "user-b") is None
    assert await store.load_latest_by_run(session_id, "run-0", "tenant-a", "user-a") is None
    assert (await store.load_latest_by_run_internal(session_id, "run-2"))["state"]["turn"] == 2
    assert len(await store.list_snapshots(session_id, "tenant-a", "user-a")) == 2
    assert await store.list_snapshots(session_id, "tenant-b", "user-a") == []
    assert await store.load_latest("missing") is None
    async with store._sessions() as session:
        assert await session.scalar(select(func.count()).select_from(RunCheckpoint)) == 3

    claims = await asyncio.gather(
        *[store.claim_resume(session_id, "run-3", f"resumed-{i}", "tenant-a", "user-a") for i in range(8)]
    )
    assert sum(claims) == 1
    monkeypatch.setattr(settings.config.checkpoint, "max_per_session", 0)
    await store.save(session_id, "run-4", "observe", state)
    assert len(await store.list_snapshots(session_id, "tenant-a", "user-a")) == 3


async def test_failed_insert_rolls_back_retention_cleanup(postgres_store, monkeypatch):
    store = postgres_store
    monkeypatch.setattr(settings.config.checkpoint, "max_per_session", 1)
    await store.save("session", "valid", "observe", {"turn": 1})
    with pytest.raises(IntegrityError):
        await store.save("session", "invalid", None, {"turn": 2})
    assert (await store.load_latest("session"))["run_id"] == "valid"
    await store.save("session", "recovered", "observe", {"turn": 3})
    assert (await store.load_latest("session"))["run_id"] == "recovered"
