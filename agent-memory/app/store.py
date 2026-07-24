import asyncio
import errno
import math
import os
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlparse, urlunparse
from uuid import uuid4

import asyncpg
from loguru import logger

from .embedding import EmbeddingClient, vector_min_similarity
from .relevance import cosine_similarity, rank, significant_terms

# 记忆信息分层取值：步骤态 / 任务态 / 跨任务长期 / 跨会话语义。
# Runtime Core 不感知具体业务语义，仅用该枚举区分记忆生命周期与召回策略。
MEMORY_KINDS = ("step", "task", "long_term", "semantic")
DEFAULT_MEMORY_KIND = "task"
TRANSIENT_POSTGRES_ERRORS = (
    asyncpg.exceptions.ConnectionDoesNotExistError,
    asyncpg.exceptions.ConnectionFailureError,
    asyncpg.exceptions.ClientCannotConnectError,
    asyncpg.exceptions.CannotConnectNowError,
    asyncpg.exceptions.AdminShutdownError,
    asyncpg.exceptions.CrashShutdownError,
    asyncpg.exceptions.TooManyConnectionsError,
    ConnectionError,
    TimeoutError,
)


def normalize_kind(kind: str | None) -> str:
    value = (kind or "").strip().lower()
    return value if value in MEMORY_KINDS else DEFAULT_MEMORY_KIND


def _search_top_k() -> int:
    return int(os.getenv("AGENT_MEMORY_SEARCH_TOP_K", "10"))


def _search_pool() -> int:
    return int(os.getenv("AGENT_MEMORY_SEARCH_POOL", "200"))


def _search_half_life_days() -> float:
    return float(os.getenv("AGENT_MEMORY_SEARCH_HALF_LIFE_DAYS", "30"))


def _bounded_int_env(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.getenv(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} 必须是整数") from exc
    if value < minimum or value > maximum:
        raise ValueError(f"{name} 必须介于 {minimum} 和 {maximum} 之间")
    return value


def _bounded_float_env(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.getenv(name, str(default)).strip()
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} 必须是数字") from exc
    if not math.isfinite(value) or value < minimum or value > maximum:
        raise ValueError(f"{name} 必须介于 {minimum} 和 {maximum} 之间")
    return value


_embedding_client = EmbeddingClient()


async def hybrid_rank(query: str, candidates: list["MemoryItem"]) -> list["MemoryItem"]:
    """候选池内的混合重排：词法 + 时间恒定参与，向量一路在 Embedding 开启且调用成功时并入。

    Embedding 关闭、无候选或调用失败时退化为两路融合，行为与向量接入前完全一致。
    """
    vector_scores: list[float] | None = None
    if candidates and _embedding_client.enabled:
        embedded = await _embedding_client.embed([query, *[item.content for item in candidates]])
        if embedded is not None:
            query_vector, doc_vectors = embedded[0], embedded[1:]
            vector_scores = [cosine_similarity(query_vector, doc_vector) for doc_vector in doc_vectors]
    return rank(
        query,
        candidates,
        _item_content,
        _item_created,
        _search_top_k(),
        half_life_days=_search_half_life_days(),
        vector_scores=vector_scores,
        vector_min_score=vector_min_similarity(),
    )


def _item_content(item: "MemoryItem") -> str:
    return item.content


def _item_created(item: "MemoryItem") -> str | None:
    return item.created_at


@dataclass
class MemoryItem:
    id: str
    scope: str
    content: str
    created_at: str
    tenant_id: str = "default-tenant"
    kind: str = DEFAULT_MEMORY_KIND
    operator_id: str | None = None
    version: int = 1
    updated_at: str | None = None
    expires_at: str | None = None


@dataclass
class MemoryRevision:
    """记忆内容的历史快照，用于回滚。"""

    version: int
    content: str
    recorded_at: str
    operator_id: str | None = None


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _expires_at(ttl_seconds: int | None) -> str | None:
    if ttl_seconds is None:
        return None
    return (_now() + timedelta(seconds=ttl_seconds)).isoformat()


def _is_expired(item: MemoryItem) -> bool:
    if not item.expires_at:
        return False
    return datetime.fromisoformat(item.expires_at) <= _now()


@dataclass
class MemoryStore:
    items: list[MemoryItem] = field(default_factory=list)
    revisions: dict[str, list[MemoryRevision]] = field(default_factory=dict)

    def add(
        self,
        scope: str,
        content: str,
        ttl_seconds: int | None = None,
        kind: str | None = None,
        operator_id: str | None = None,
        tenant_id: str = "default-tenant",
    ) -> MemoryItem:
        item = MemoryItem(
            id=f"mem_{uuid4().hex[:12]}",
            scope=scope,
            content=content,
            created_at=_now().isoformat(),
            tenant_id=tenant_id,
            kind=normalize_kind(kind),
            operator_id=operator_id or "anonymous",
            expires_at=_expires_at(ttl_seconds),
        )
        self.items.append(item)
        return item

    async def search(
        self,
        query: str,
        scope: str | None = None,
        *,
        tenant_id: str = "default-tenant",
        operator_id: str = "anonymous",
    ) -> list[MemoryItem]:
        self.purge_expired()
        candidates = [
            item
            for item in self.items
            if item.tenant_id == tenant_id
            and item.operator_id == operator_id
            and (scope is None or item.scope == scope)
        ]
        return await hybrid_rank(query, candidates)

    def _record_revision(self, item: MemoryItem, operator_id: str | None) -> None:
        self.revisions.setdefault(item.id, []).append(
            MemoryRevision(
                version=item.version,
                content=item.content,
                recorded_at=item.updated_at or item.created_at,
                operator_id=operator_id if operator_id is not None else item.operator_id,
            )
        )

    def update(
        self,
        item_id: str,
        content: str,
        ttl_seconds: int | None = None,
        operator_id: str | None = None,
        tenant_id: str = "default-tenant",
    ) -> MemoryItem | None:
        self.purge_expired()
        for item in self.items:
            if item.id == item_id and item.tenant_id == tenant_id and item.operator_id == operator_id:
                self._record_revision(item, operator_id)
                item.content = content
                item.updated_at = _now().isoformat()
                item.version += 1
                if operator_id is not None:
                    item.operator_id = operator_id
                if ttl_seconds is not None:
                    item.expires_at = _expires_at(ttl_seconds)
                return item
        return None

    def rollback(
        self, item_id: str, operator_id: str | None = None, tenant_id: str = "default-tenant"
    ) -> MemoryItem | None:
        self.purge_expired()
        history = self.revisions.get(item_id)
        if not history:
            return None
        for item in self.items:
            if item.id == item_id and item.tenant_id == tenant_id and item.operator_id == operator_id:
                previous = history.pop()
                item.content = previous.content
                item.updated_at = _now().isoformat()
                item.version += 1
                if operator_id is not None:
                    item.operator_id = operator_id
                return item
        return None

    def revision_count(self, item_id: str) -> int:
        return len(self.revisions.get(item_id, []))

    def delete(self, item_id: str, *, tenant_id: str, operator_id: str) -> bool:
        before = len(self.items)
        self.items = [
            item
            for item in self.items
            if not (item.id == item_id and item.tenant_id == tenant_id and item.operator_id == operator_id)
        ]
        deleted = len(self.items) < before
        if deleted:
            self.revisions.pop(item_id, None)
        return deleted

    def purge_expired(self) -> int:
        before = len(self.items)
        survivors = [item for item in self.items if not _is_expired(item)]
        expired_ids = {item.id for item in self.items} - {item.id for item in survivors}
        for expired_id in expired_ids:
            self.revisions.pop(expired_id, None)
        self.items = survivors
        return before - len(self.items)


class PostgresMemoryStore:
    """agent-memory PostgreSQL 本地持久化存储。"""

    def __init__(self, dsn: str | None = None):
        self.dsn = (
            dsn
            or os.getenv("AGENT_MEMORY_DATABASE_URL")
            or os.getenv("DATABASE_URL")
            or self._spring_datasource_dsn()
            or ""
        ).strip()
        self.pool: asyncpg.Pool | None = None

    @property
    def enabled(self) -> bool:
        return bool(self.dsn)

    async def connect(self) -> None:
        if not self.enabled or self.pool is not None:
            return
        parsed = urlparse(self.dsn)
        target = f"{parsed.hostname or '<unknown>'}:{parsed.port or 5432}"
        attempts = _bounded_int_env("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", 4, 1, 10)
        connect_timeout = _bounded_float_env("AGENT_MEMORY_DB_CONNECT_TIMEOUT_SECONDS", 8.0, 0.5, 60.0)
        base_backoff = _bounded_float_env("AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS", 0.5, 0.0, 30.0)
        ssl_mode = self._database_ssl_mode()
        last_error: Exception | None = None

        for attempt in range(1, attempts + 1):
            try:
                self.pool = await asyncpg.create_pool(
                    dsn=self.dsn,
                    min_size=1,
                    max_size=int(os.getenv("AGENT_MEMORY_DB_POOL_SIZE", "5")),
                    command_timeout=float(os.getenv("AGENT_MEMORY_DB_CMD_TIMEOUT", "5")),
                    timeout=connect_timeout,
                    ssl=ssl_mode,
                )
                await self.init_schema()
                return
            except Exception as exc:
                last_error = exc
                await self._discard_pool()
                if not self._is_transient_connection_error(exc) or attempt >= attempts:
                    break
                delay_seconds = min(base_backoff * (2 ** (attempt - 1)), 30.0)
                logger.warning(
                    "agent-memory PostgreSQL 瞬时连接失败，准备重试："
                    "target={}, attempt={}/{}, delay_seconds={}, error_type={}",
                    target,
                    attempt,
                    attempts,
                    delay_seconds,
                    type(exc).__name__,
                )
                if delay_seconds > 0:
                    await asyncio.sleep(delay_seconds)

        attempt_summary = (
            f"，已尝试 {attempts} 次"
            if last_error is not None and self._is_transient_connection_error(last_error)
            else ""
        )
        raise RuntimeError(
            "agent-memory 无法连接 PostgreSQL "
            f"({target}{attempt_summary})；请检查网络可达性、数据库负载、地址、端口及 "
            "AGENT_MEMORY_DB_SSL_MODE "
            "（本地无 TLS 使用 disable，生产环境使用 require/verify-full）"
        ) from last_error

    @staticmethod
    def _is_transient_connection_error(error: Exception) -> bool:
        if isinstance(error, TRANSIENT_POSTGRES_ERRORS):
            return True
        return isinstance(error, OSError) and error.errno in {
            errno.ECONNABORTED,
            errno.ECONNREFUSED,
            errno.ECONNRESET,
            errno.EHOSTDOWN,
            errno.EHOSTUNREACH,
            errno.ENETDOWN,
            errno.ENETUNREACH,
            errno.ETIMEDOUT,
        }

    async def _discard_pool(self) -> None:
        pool = self.pool
        self.pool = None
        if pool is None:
            return
        try:
            await asyncio.wait_for(pool.close(), timeout=2.0)
        except Exception:
            pool.terminate()

    def _database_ssl_mode(self) -> str:
        configured = os.getenv("AGENT_MEMORY_DB_SSL_MODE", "").strip().lower()
        if not configured:
            query = urlparse(self.dsn).query
            for part in query.split("&"):
                key, separator, value = part.partition("=")
                if separator and key.lower() == "sslmode":
                    configured = value.strip().lower()
                    break
        mode = configured or "disable"
        allowed = {"disable", "prefer", "allow", "require", "verify-ca", "verify-full"}
        if mode not in allowed:
            raise ValueError("AGENT_MEMORY_DB_SSL_MODE 仅支持 disable/prefer/allow/require/verify-ca/verify-full")
        return mode

    async def close(self) -> None:
        if self.pool is not None:
            await self.pool.close()
            self.pool = None

    async def init_schema(self) -> None:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_memory_items (
                    id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL DEFAULT 'default-tenant',
                    scope TEXT NOT NULL,
                    content TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'task',
                    operator_id TEXT NOT NULL DEFAULT 'anonymous',
                    version INTEGER NOT NULL DEFAULT 1,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ,
                    expires_at TIMESTAMPTZ
                );

                CREATE TABLE IF NOT EXISTS agent_memory_revisions (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    memory_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    operator_id TEXT,
                    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    CONSTRAINT fk_agent_memory_revision_item
                        FOREIGN KEY (memory_id) REFERENCES agent_memory_items(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_agent_memory_revisions_memory_id
                    ON agent_memory_revisions (memory_id, version DESC);
                CREATE INDEX IF NOT EXISTS idx_agent_memory_items_owner_scope_created
                    ON agent_memory_items (tenant_id, operator_id, scope, created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_agent_memory_items_content_lower
                    ON agent_memory_items (LOWER(content) text_pattern_ops);
                """
            )

    async def add(
        self,
        scope: str,
        content: str,
        ttl_seconds: int | None = None,
        kind: str | None = None,
        operator_id: str | None = None,
        tenant_id: str = "default-tenant",
    ) -> MemoryItem:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        item_id = f"mem_{uuid4().hex[:12]}"
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_memory_items (id, tenant_id, scope, content, kind, operator_id, expires_at)
                VALUES ($1, $2, $3, $4, $5, $6, CASE WHEN $7::int IS NULL THEN NULL ELSE NOW() + ($7::int * INTERVAL '1 second') END)
                RETURNING id, tenant_id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                """,
                item_id,
                tenant_id,
                scope,
                content,
                normalize_kind(kind),
                operator_id or "anonymous",
                ttl_seconds,
            )
        return self._row_to_item(row)

    async def search(
        self,
        query: str,
        scope: str | None = None,
        *,
        tenant_id: str = "default-tenant",
        operator_id: str = "anonymous",
    ) -> list[MemoryItem]:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        terms = significant_terms(query)
        patterns = [f"%{term}%" for term in terms]
        pool_size = _search_pool()
        async with self.pool.acquire() as conn:
            if patterns:
                rows = await conn.fetch(
                    """
                    SELECT id, tenant_id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                    FROM agent_memory_items
                    WHERE tenant_id = $1 AND operator_id = $2
                      AND ($3::text IS NULL OR scope = $3)
                      AND LOWER(content) ILIKE ANY($4::text[])
                      AND (expires_at IS NULL OR expires_at > NOW())
                    ORDER BY created_at DESC
                    LIMIT $5
                    """,
                    tenant_id,
                    operator_id,
                    scope,
                    patterns,
                    pool_size,
                )
            else:
                rows = await conn.fetch(
                    """
                    SELECT id, tenant_id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                    FROM agent_memory_items
                    WHERE tenant_id = $1 AND operator_id = $2
                      AND ($3::text IS NULL OR scope = $3)
                      AND (expires_at IS NULL OR expires_at > NOW())
                    ORDER BY created_at DESC
                    LIMIT $4
                    """,
                    tenant_id,
                    operator_id,
                    scope,
                    pool_size,
                )
        candidates = [self._row_to_item(row) for row in rows]
        return await hybrid_rank(query, candidates)

    async def update(
        self,
        item_id: str,
        content: str,
        ttl_seconds: int | None = None,
        operator_id: str | None = None,
        tenant_id: str = "default-tenant",
    ) -> MemoryItem | None:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                current = await conn.fetchrow(
                    """
                    SELECT id, content, version, operator_id, updated_at, created_at
                    FROM agent_memory_items
                    WHERE id = $1 AND tenant_id = $2 AND operator_id = $3
                      AND (expires_at IS NULL OR expires_at > NOW())
                    FOR UPDATE
                    """,
                    item_id,
                    tenant_id,
                    operator_id or "anonymous",
                )
                if current is None:
                    return None
                await conn.execute(
                    """
                    INSERT INTO agent_memory_revisions (memory_id, version, content, operator_id, recorded_at)
                    VALUES ($1, $2, $3, $4, COALESCE($5, $6, NOW()))
                    """,
                    item_id,
                    current["version"],
                    current["content"],
                    current["operator_id"],
                    current["updated_at"],
                    current["created_at"],
                )
                row = await conn.fetchrow(
                    """
                    UPDATE agent_memory_items
                    SET content = $4,
                        updated_at = NOW(),
                        version = version + 1,
                        expires_at = CASE WHEN $5::int IS NULL THEN expires_at ELSE NOW() + ($5::int * INTERVAL '1 second') END
                    WHERE id = $1 AND tenant_id = $2 AND operator_id = $3
                    RETURNING id, tenant_id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                    """,
                    item_id,
                    tenant_id,
                    operator_id or "anonymous",
                    content,
                    ttl_seconds,
                )
        return self._row_to_item(row) if row else None

    async def rollback(
        self, item_id: str, operator_id: str | None = None, tenant_id: str = "default-tenant"
    ) -> MemoryItem | None:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                revision = await conn.fetchrow(
                    """
                    SELECT r.id, r.content
                    FROM agent_memory_revisions r
                    JOIN agent_memory_items m ON m.id = r.memory_id
                    WHERE r.memory_id = $1 AND m.tenant_id = $2 AND m.operator_id = $3
                    ORDER BY r.version DESC
                    LIMIT 1
                    """,
                    item_id,
                    tenant_id,
                    operator_id or "anonymous",
                )
                if revision is None:
                    return None
                row = await conn.fetchrow(
                    """
                    UPDATE agent_memory_items
                    SET content = $4,
                        updated_at = NOW(),
                        version = version + 1
                    WHERE id = $1 AND tenant_id = $2 AND operator_id = $3
                      AND (expires_at IS NULL OR expires_at > NOW())
                    RETURNING id, tenant_id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                    """,
                    item_id,
                    tenant_id,
                    operator_id or "anonymous",
                    revision["content"],
                )
                if row is None:
                    return None
                await conn.execute("DELETE FROM agent_memory_revisions WHERE id = $1", revision["id"])
        return self._row_to_item(row)

    async def delete(self, item_id: str, *, tenant_id: str, operator_id: str) -> bool:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                owned = await conn.fetchval(
                    "SELECT 1 FROM agent_memory_items WHERE id = $1 AND tenant_id = $2 AND operator_id = $3",
                    item_id,
                    tenant_id,
                    operator_id,
                )
                if not owned:
                    return False
                await conn.execute("DELETE FROM agent_memory_revisions WHERE memory_id = $1", item_id)
                result = await conn.execute(
                    "DELETE FROM agent_memory_items WHERE id = $1 AND tenant_id = $2 AND operator_id = $3",
                    item_id,
                    tenant_id,
                    operator_id,
                )
        return result.endswith("1")

    async def purge_expired(self) -> int:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """
                    DELETE FROM agent_memory_revisions
                    WHERE memory_id IN (
                        SELECT id FROM agent_memory_items WHERE expires_at IS NOT NULL AND expires_at <= NOW()
                    )
                    """
                )
                result = await conn.execute(
                    "DELETE FROM agent_memory_items WHERE expires_at IS NOT NULL AND expires_at <= NOW()"
                )
        try:
            return int(result.rsplit(" ", 1)[-1])
        except ValueError:
            return 0

    @staticmethod
    def _spring_datasource_dsn() -> str:
        jdbc_url = os.getenv("SPRING_DATASOURCE_URL", "").strip()
        username = os.getenv("SPRING_DATASOURCE_USERNAME", "").strip()
        password = os.getenv("SPRING_DATASOURCE_PASSWORD", "").strip()
        if not jdbc_url.startswith("jdbc:postgresql://"):
            return ""

        parsed = urlparse(jdbc_url.removeprefix("jdbc:"))
        if not parsed.hostname:
            return ""

        credentials = ""
        if username:
            credentials = quote(username, safe="")
            if password:
                credentials = f"{credentials}:{quote(password, safe='')}"
            credentials = f"{credentials}@"
        host = parsed.hostname
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"
        netloc = f"{credentials}{host}"
        if parsed.port:
            netloc = f"{netloc}:{parsed.port}"
        return urlunparse(("postgresql", netloc, parsed.path, "", parsed.query, ""))

    @staticmethod
    def _row_to_item(row: asyncpg.Record) -> MemoryItem:
        def iso(value):
            if value is None:
                return None
            return value.isoformat() if hasattr(value, "isoformat") else value

        return MemoryItem(
            id=row["id"],
            scope=row["scope"],
            content=row["content"],
            created_at=iso(row["created_at"]),
            tenant_id=row["tenant_id"] if "tenant_id" in row else "default-tenant",
            kind=row["kind"] if "kind" in row else DEFAULT_MEMORY_KIND,
            operator_id=row["operator_id"] if "operator_id" in row else None,
            version=row["version"] if "version" in row else 1,
            updated_at=iso(row["updated_at"]),
            expires_at=iso(row["expires_at"]),
        )
