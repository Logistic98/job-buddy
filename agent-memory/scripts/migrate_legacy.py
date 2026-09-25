"""Offline, resumable copy of legacy memory rows into Mem0. Source tables stay untouched."""

import argparse
import asyncio
import sys
from pathlib import Path
from uuid import UUID

import asyncpg
from sqlalchemy import MetaData, Table, func, select
from sqlalchemy.ext.asyncio import create_async_engine

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.env import load_root_dotenv  # noqa: E402
from app.store import MemoryStore, build_memory, database_url, owner_key  # noqa: E402


async def migrate(apply):
    import os

    async def connect():
        return await asyncpg.connect(
            database_url(),
            timeout=8,
            command_timeout=10,
            ssl=os.getenv("AGENT_MEMORY_DB_SSL_MODE", "require"),
        )

    engine = create_async_engine("postgresql+asyncpg://", async_creator=connect)
    store = None
    try:
        async with engine.connect() as connection:
            connection = await connection.execution_options(
                isolation_level="REPEATABLE READ",
                postgresql_readonly=True,
            )
            async with connection.begin():
                metadata = MetaData()
                items = await connection.run_sync(
                    lambda sync: Table("agent_memory_items", metadata, autoload_with=sync)
                )
                count = await connection.scalar(select(func.count()).select_from(items))
                if not apply:
                    print(
                        f"dry_run: legacy_rows={count}; stop service writes and back up database/history before --apply"
                    )
                    return
                store = MemoryStore(build_memory())
                copied = skipped = 0
                revisions_table = await connection.run_sync(
                    lambda sync: Table("agent_memory_revisions", metadata, autoload_with=sync)
                )
                async with connection.stream(select(items).order_by(items.c.id)) as rows:
                    async for row in rows.mappings():
                        identity = {"tenant_id": row["tenant_id"], "operator_id": row["operator_id"]}
                        filters = {"user_id": owner_key(**identity), "legacy_id": row["id"]}
                        existing = store.memory.get_all(filters=filters, top_k=1, show_expired=True)["results"]
                        if existing:
                            if existing[0]["memory"] != row["content"] or not existing[0]["metadata"].get(
                                "migration_complete"
                            ):
                                raise RuntimeError(
                                    "Incomplete or conflicting migration; inspect destination before retrying"
                                )
                            skipped += 1
                            continue
                        revisions = (
                            (
                                await connection.execute(
                                    select(revisions_table)
                                    .where(revisions_table.c.memory_id == row["id"])
                                    .order_by(revisions_table.c.version)
                                )
                            )
                            .mappings()
                            .all()
                        )
                        meta = dict(
                            identity,
                            scope=row["scope"],
                            kind=row["kind"],
                            source=row["source"],
                            enabled=row["enabled"],
                            legacy_id=row["id"],
                            version=row["version"],
                            expires_epoch=row["expires_at"].timestamp() if row["expires_at"] else 253402300799.0,
                            expires_at=row["expires_at"].isoformat() if row["expires_at"] else None,
                            created_at=row["created_at"].isoformat(),
                            migration_complete=False,
                        )
                        result = store.memory.add(
                            row["content"], user_id=owner_key(**identity), metadata=meta, infer=False
                        )
                        raw_id = result["results"][0]["id"]
                        rollback_ids = []
                        for revision in revisions:
                            store.memory.db.add_history(
                                raw_id,
                                None,
                                revision["content"],
                                "ADD",
                                created_at=revision["recorded_at"].isoformat(),
                                updated_at=revision["recorded_at"].isoformat(),
                            )
                            history = store.memory.history(raw_id)
                            copied_history = [h for h in history if h["new_memory"] == revision["content"]]
                            rollback_ids.append(copied_history[-1]["id"])
                        meta.update(rollback_ids=rollback_ids, migration_complete=True)
                        store.memory.update(raw_id, metadata=meta)
                        actual = store.memory.get(raw_id)
                        if actual["memory"] != row["content"] or actual["metadata"]["legacy_id"] != row["id"]:
                            raise RuntimeError("Migration verification failed")
                        UUID(raw_id)
                        copied += 1
        print(f"verified: copied={copied}, already_copied={skipped}, source_rows={count}; source tables unchanged")
    finally:
        await engine.dispose()
        if store:
            store.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    load_root_dotenv()
    try:
        asyncio.run(migrate(args.apply))
    except Exception as exc:
        print(f"migration_failed: {type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1) from None
