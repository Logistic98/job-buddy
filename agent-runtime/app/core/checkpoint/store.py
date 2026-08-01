"""按脱敏和持久化策略保存可恢复的 Agent 状态。"""

import json
import os
import time
from hashlib import sha256
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
    """支持 PostgreSQL 持久化和测试用内存模式的检查点存储。"""

    def __init__(self, database_url: str | None = None):
        # Runtime 持久化使用独立变量，不继承 agent-memory 的 DSN。
        if database_url is not None:
            self._database_url = database_url.strip()
        else:
            self._database_url = os.getenv("AGENT_RUNTIME_DATABASE_URL", "").strip()
        self._pool: asyncpg.Pool | None = None
        self._memory: list[Dict[str, Any]] = []
        self._memory_resume_claims: set[tuple[str, str, str, str]] = set()
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
        persisted_state = self._summarize_execution_payloads(self._json_safe(persisted_state))
        payload = {
            "session_id": session_id,
            "run_id": run_id,
            "stage": stage,
            "saved_at": TimeUtils.get_formatted_time(),
            "state": redact_sensitive(persisted_state),
        }
        sequence = time.time_ns()
        tenant_id, user_id = self._state_owner(state)
        pool = await self._get_pool()
        if pool is None:
            self._memory.append({**payload, "sequence": sequence})
            self._cleanup_memory(session_id)
            return
        encoded = json.dumps(payload, ensure_ascii=False)
        async with pool.acquire() as conn:
            max_count = settings.config.checkpoint.max_per_session
            if max_count > 0:
                await conn.execute(
                    """
                    WITH inserted AS (
                      INSERT INTO agent_run_checkpoint(
                        session_id, run_id, stage, sequence, payload_json, tenant_id, user_id, created_at
                      )
                      VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, CURRENT_TIMESTAMP)
                      RETURNING id
                    ), deleted AS (
                      DELETE FROM agent_run_checkpoint
                      WHERE id IN (
                        SELECT id FROM agent_run_checkpoint
                        WHERE session_id = $1
                        ORDER BY sequence DESC
                        OFFSET $8
                      )
                      RETURNING id
                    )
                    SELECT id FROM inserted
                    """,
                    session_id,
                    run_id,
                    stage,
                    sequence,
                    encoded,
                    tenant_id or None,
                    user_id or None,
                    max_count - 1,
                )
            else:
                await conn.execute(
                    """
                    INSERT INTO agent_run_checkpoint(
                      session_id, run_id, stage, sequence, payload_json, tenant_id, user_id, created_at
                    )
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, CURRENT_TIMESTAMP)
                    """,
                    session_id,
                    run_id,
                    stage,
                    sequence,
                    encoded,
                    tenant_id or None,
                    user_id or None,
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

    async def load_latest_by_run(
        self,
        session_id: str,
        run_id: str,
        tenant_id: str,
        user_id: str,
    ) -> Optional[Dict[str, Any]]:
        """按来源归属读取用户可恢复的指定运行。"""

        return await self._load_latest_by_run(session_id, run_id, tenant_id, user_id)

    async def load_latest_by_run_internal(
        self,
        session_id: str,
        run_id: str,
    ) -> Optional[Dict[str, Any]]:
        """读取执行器当前生成的内部运行；不得用于外部请求或恢复来源查询。"""

        return await self._load_latest_by_run(session_id, run_id, None, None)

    async def _load_latest_by_run(
        self,
        session_id: str,
        run_id: str,
        tenant_id: str | None,
        user_id: str | None,
    ) -> Optional[Dict[str, Any]]:
        pool = await self._get_pool()
        if pool is None:
            rows = [row for row in self._memory_rows(session_id) if row.get("run_id") == run_id]
            if tenant_id is not None or user_id is not None:
                rows = [row for row in rows if self._row_owned_by(row, tenant_id or "", user_id or "")]
            return self._public_payload(rows[0]) if rows else None
        async with pool.acquire() as conn:
            if tenant_id is None and user_id is None:
                value = await conn.fetchval(
                    """
                    SELECT payload_json::text FROM agent_run_checkpoint
                    WHERE session_id=$1 AND run_id=$2
                    ORDER BY sequence DESC LIMIT 1
                    """,
                    session_id,
                    run_id,
                )
            else:
                value = await conn.fetchval(
                    """
                    SELECT payload_json::text FROM agent_run_checkpoint
                    WHERE session_id=$1 AND run_id=$2 AND tenant_id=$3 AND user_id=$4
                    ORDER BY sequence DESC LIMIT 1
                    """,
                    session_id,
                    run_id,
                    tenant_id or "",
                    user_id or "",
                )
        return json.loads(value) if value else None

    async def claim_resume(
        self,
        session_id: str,
        source_run_id: str,
        resumed_run_id: str,
        tenant_id: str = "",
        user_id: str = "",
    ) -> bool:
        """原子领取来源运行的唯一续跑权，防止重复点击并发执行。"""

        pool = await self._get_pool()
        if pool is None:
            key = (session_id, source_run_id, tenant_id or "", user_id or "")
            if key in self._memory_resume_claims:
                return False
            self._memory_resume_claims.add(key)
            return True
        async with pool.acquire() as conn:
            claimed = await conn.fetchval(
                """
                INSERT INTO agent_run_resume_claim(
                  session_id, source_run_id, resumed_run_id, tenant_id, user_id, claimed_at
                )
                VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP)
                ON CONFLICT (session_id, source_run_id) DO NOTHING
                RETURNING id
                """,
                session_id,
                source_run_id,
                resumed_run_id,
                tenant_id or "",
                user_id or "",
            )
        return claimed is not None

    async def list_snapshots(
        self,
        session_id: str,
        tenant_id: str,
        user_id: str,
    ) -> list[Dict[str, Any]]:
        pool = await self._get_pool()
        if pool is None:
            payloads = [
                self._public_payload(row)
                for row in self._memory_rows(session_id)
                if self._row_owned_by(row, tenant_id, user_id)
            ]
        else:
            async with pool.acquire() as conn:
                rows = await conn.fetch(
                    """
                    SELECT payload_json::text AS payload FROM agent_run_checkpoint
                    WHERE session_id=$1 AND tenant_id=$2 AND user_id=$3
                    ORDER BY sequence DESC
                    """,
                    session_id,
                    tenant_id,
                    user_id,
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

    def _state_owner(self, state: Dict[str, Any]) -> tuple[str, str]:
        metadata = state.get("metadata") if isinstance(state.get("metadata"), dict) else {}
        tenant_id = str(metadata.get("tenant_id") or "").strip()
        user_id = str(metadata.get("user_id") or metadata.get("operator_id") or "").strip()
        return tenant_id, user_id

    def _row_owned_by(self, row: Dict[str, Any], tenant_id: str, user_id: str) -> bool:
        state = row.get("state") if isinstance(row.get("state"), dict) else {}
        return self._state_owner(state) == (tenant_id, user_id)

    def _json_safe(self, obj: Any):
        if hasattr(obj, "model_dump"):
            return obj.model_dump()
        if isinstance(obj, dict):
            return {key: self._json_safe(value) for key, value in obj.items()}
        if isinstance(obj, list):
            return [self._json_safe(item) for item in obj]
        return obj

    def _persistence_snapshot(self, state: Dict[str, Any]) -> Dict[str, Any]:
        """持久化前移除可重建的个人上下文副本。

        活跃内存状态保持不变。原始消息和个人上下文由恢复请求重建，持久化仅保留
        非个人上下文骨架、观察、计划与工具状态。
        """

        snapshot = dict(state)
        metadata = dict(snapshot.get("metadata") or {})
        metadata.pop("personal_context", None)
        attachments = metadata.get("attachments")
        if isinstance(attachments, list):
            metadata["attachments"] = [
                self._attachment_reference(item) for item in attachments if isinstance(item, dict)
            ]
        snapshot["metadata"] = metadata
        messages = snapshot.get("messages")
        if isinstance(messages, list):
            for message in reversed(messages):
                role = (
                    message.role
                    if hasattr(message, "role")
                    else message.get("role")
                    if isinstance(message, dict)
                    else None
                )
                content = (
                    message.content
                    if hasattr(message, "content")
                    else message.get("content")
                    if isinstance(message, dict)
                    else None
                )
                if role == "user":
                    snapshot["_resume_message_sha256"] = sha256(
                        str(content or "").encode("utf-8", errors="replace")
                    ).hexdigest()
                    break
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

    @staticmethod
    def _attachment_reference(item: Dict[str, Any]) -> Dict[str, Any]:
        blocked = {
            "content",
            "extractedText",
            "extracted_text",
            "untrusted",
            "injectionHits",
            "injection_hits",
        }
        return {key: value for key, value in item.items() if str(key) not in blocked}

    def _summarize_execution_payloads(self, value: Any, parent_key: str = "") -> Any:
        """检查点只保存可审计摘要，不保存可执行源码、命令、argv 或原始进程输出。"""

        if parent_key == "observations" and isinstance(value, list):
            return [
                (
                    "[SANDBOX_OUTPUT_REDACTED]"
                    if isinstance(item, str) and "sandbox_code_execute" in item
                    else self._summarize_execution_payloads(item)
                )
                for item in value
            ]
        if isinstance(value, dict):
            summarized: Dict[str, Any] = {}
            for key, item in value.items():
                normalized = str(key).strip().lower()
                if normalized in {"code", "command", "argv", "stdout", "stderr"}:
                    summarized[str(key)] = self._execution_value_summary(item)
                else:
                    summarized[str(key)] = self._summarize_execution_payloads(item, normalized)
            return summarized
        if isinstance(value, list):
            return [self._summarize_execution_payloads(item) for item in value]
        return value

    def _execution_value_summary(self, value: Any) -> Dict[str, Any]:
        text = (
            value
            if isinstance(value, str)
            else json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)
        )
        encoded = text.encode("utf-8", errors="replace")
        return {
            "redacted": True,
            "chars": len(text),
            "sha256": sha256(encoded).hexdigest(),
        }
