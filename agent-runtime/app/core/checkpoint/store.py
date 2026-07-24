import json
import os
import time
from threading import Lock
from typing import Any, Dict, Optional

import asyncpg
from loguru import logger

from app.core.common.settings import settings
from app.core.security.redaction import redact_sensitive
from app.core.utils.time_utils import TimeUtils

_missing_dsn_warning_emitted = False
_missing_dsn_warning_lock = Lock()


class CheckpointStore:
    """PostgreSQL checkpoint store with an in-memory mode for tests and local runs."""

    def __init__(self, database_url: str | None = None):
        # Runtime persistence uses its own variable and never inherits agent-memory's DSN.
        if database_url is not None:
            self._database_url = database_url.strip()
        else:
            self._database_url = os.getenv("AGENT_RUNTIME_DATABASE_URL", "").strip()
        self._pool: asyncpg.Pool | None = None
        self._memory: list[Dict[str, Any]] = []
        self._warn_memory_fallback_once()

    def _warn_memory_fallback_once(self) -> None:
        global _missing_dsn_warning_emitted
        if self._database_url or not settings.config.checkpoint.enabled:
            return
        with _missing_dsn_warning_lock:
            if _missing_dsn_warning_emitted:
                return
            _missing_dsn_warning_emitted = True
        logger.warning("Checkpoint 已开启但未配置 AGENT_RUNTIME_DATABASE_URL，将降级为进程内存存储；进程重启后不可恢复")

    async def _get_pool(self) -> asyncpg.Pool | None:
        if not self._database_url:
            return None
        if self._pool is None:
            self._pool = await asyncpg.create_pool(
                dsn=self._database_url,
                min_size=1,
                max_size=5,
                command_timeout=10,
            )
        return self._pool

    async def save(self, session_id: str, run_id: str, stage: str, state: Dict[str, Any]):
        if not settings.config.checkpoint.enabled:
            return
        persisted_state = self._persistence_snapshot(state)
        payload = {
            "session_id": session_id,
            "run_id": run_id,
            "stage": stage,
            "saved_at": TimeUtils.get_formatted_time(),
            "state": self._json_safe(redact_sensitive(persisted_state)),
        }
        sequence = time.time_ns()
        pool = await self._get_pool()
        if pool is None:
            self._memory.append({**payload, "sequence": sequence})
            self._cleanup_memory(session_id)
            return
        encoded = json.dumps(payload, ensure_ascii=False)
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO agent_run_checkpoint(session_id, run_id, stage, sequence, payload_json, created_at)
                VALUES ($1, $2, $3, $4, $5::jsonb, CURRENT_TIMESTAMP)
                """,
                session_id,
                run_id,
                stage,
                sequence,
                encoded,
            )
            max_count = settings.config.checkpoint.max_per_session
            if max_count > 0:
                await conn.execute(
                    """
                    DELETE FROM agent_run_checkpoint
                    WHERE id IN (
                      SELECT id FROM agent_run_checkpoint
                      WHERE session_id = $1
                      ORDER BY sequence DESC
                      OFFSET $2
                    )
                    """,
                    session_id,
                    max_count,
                )

    async def load_latest(self, session_id: str) -> Optional[Dict[str, Any]]:
        pool = await self._get_pool()
        if pool is None:
            rows = self._memory_rows(session_id)
            return self._public_payload(rows[0]) if rows else None
        async with pool.acquire() as conn:
            value = await conn.fetchval(
                "SELECT payload_json::text FROM agent_run_checkpoint WHERE session_id=$1 ORDER BY sequence DESC LIMIT 1",
                session_id,
            )
        return json.loads(value) if value else None

    async def load_latest_by_run(self, session_id: str, run_id: str) -> Optional[Dict[str, Any]]:
        pool = await self._get_pool()
        if pool is None:
            rows = [row for row in self._memory_rows(session_id) if row.get("run_id") == run_id]
            return self._public_payload(rows[0]) if rows else None
        async with pool.acquire() as conn:
            value = await conn.fetchval(
                """
                SELECT payload_json::text FROM agent_run_checkpoint
                WHERE session_id=$1 AND run_id=$2
                ORDER BY sequence DESC LIMIT 1
                """,
                session_id,
                run_id,
            )
        return json.loads(value) if value else None

    async def list_snapshots(self, session_id: str) -> list[Dict[str, Any]]:
        pool = await self._get_pool()
        if pool is None:
            payloads = [self._public_payload(row) for row in self._memory_rows(session_id)]
        else:
            async with pool.acquire() as conn:
                rows = await conn.fetch(
                    """
                    SELECT payload_json::text AS payload FROM agent_run_checkpoint
                    WHERE session_id=$1 ORDER BY sequence DESC
                    """,
                    session_id,
                )
            payloads = [json.loads(row["payload"]) for row in rows]
        return [
            {
                "session_id": item.get("session_id"),
                "run_id": item.get("run_id"),
                "stage": item.get("stage"),
                "saved_at": item.get("saved_at"),
                "storage": "postgresql" if pool is not None else "memory",
            }
            for item in payloads
        ]

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    def _memory_rows(self, session_id: str) -> list[Dict[str, Any]]:
        return sorted(
            (row for row in self._memory if row.get("session_id") == session_id),
            key=lambda row: int(row.get("sequence") or 0),
            reverse=True,
        )

    def _cleanup_memory(self, session_id: str) -> None:
        max_count = settings.config.checkpoint.max_per_session
        if max_count <= 0:
            return
        keep = {id(row) for row in self._memory_rows(session_id)[:max_count]}
        self._memory = [row for row in self._memory if row.get("session_id") != session_id or id(row) in keep]

    def _public_payload(self, row: Dict[str, Any]) -> Dict[str, Any]:
        return {key: value for key, value in row.items() if key != "sequence"}

    def _json_safe(self, obj: Any):
        if hasattr(obj, "model_dump"):
            return obj.model_dump()
        if isinstance(obj, dict):
            return {key: self._json_safe(value) for key, value in obj.items()}
        if isinstance(obj, list):
            return [self._json_safe(item) for item in obj]
        return obj

    def _persistence_snapshot(self, state: Dict[str, Any]) -> Dict[str, Any]:
        """Drop reconstructable personal-context copies before durable persistence.

        The active in-memory state remains unchanged. Raw messages and personal context are
        reconstructed from the resume request; only the non-personal context skeleton,
        observations, plan, and tool state remain durable.
        """

        snapshot = dict(state)
        metadata = dict(snapshot.get("metadata") or {})
        metadata.pop("personal_context", None)
        snapshot["metadata"] = metadata
        snapshot.pop("messages", None)
        snapshot.pop("context_payload", None)
        context_summary = snapshot.get("context_summary")
        if isinstance(context_summary, str):
            try:
                resumable_context = json.loads(context_summary)
            except (TypeError, ValueError):
                snapshot.pop("context_summary", None)
            else:
                if isinstance(resumable_context, dict):
                    resumable_context.pop("personal_context", None)
                    resumable_context.pop("recent_messages", None)
                    snapshot["context_summary"] = json.dumps(
                        resumable_context,
                        ensure_ascii=False,
                        separators=(",", ":"),
                        sort_keys=True,
                    )
                else:
                    snapshot.pop("context_summary", None)
        return snapshot
