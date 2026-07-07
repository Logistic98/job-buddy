import os
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlparse, urlunparse
from uuid import uuid4

import asyncpg

from .embedding import EmbeddingClient, vector_min_similarity
from .relevance import cosine_similarity, rank, significant_terms

# 记忆信息分层取值：步骤态 / 任务态 / 跨任务长期 / 跨会话语义。
# Runtime Core 不感知具体业务语义，仅用该枚举区分记忆生命周期与召回策略。
MEMORY_KINDS = ("step", "task", "long_term", "semantic")
DEFAULT_MEMORY_KIND = "task"


def normalize_kind(kind: str | None) -> str:
    value = (kind or "").strip().lower()
    return value if value in MEMORY_KINDS else DEFAULT_MEMORY_KIND


def _search_top_k() -> int:
    return int(os.getenv("AGENT_MEMORY_SEARCH_TOP_K", "10"))


def _search_pool() -> int:
    return int(os.getenv("AGENT_MEMORY_SEARCH_POOL", "200"))


def _search_half_life_days() -> float:
    return float(os.getenv("AGENT_MEMORY_SEARCH_HALF_LIFE_DAYS", "30"))


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
    ) -> MemoryItem:
        item = MemoryItem(
            id=f"mem_{uuid4().hex[:12]}",
            scope=scope,
            content=content,
            created_at=_now().isoformat(),
            kind=normalize_kind(kind),
            operator_id=operator_id,
            expires_at=_expires_at(ttl_seconds),
        )
        self.items.append(item)
        return item

    async def search(self, query: str, scope: str | None = None) -> list[MemoryItem]:
        self.purge_expired()
        candidates = [item for item in self.items if scope is None or item.scope == scope]
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
    ) -> MemoryItem | None:
        self.purge_expired()
        for item in self.items:
            if item.id == item_id:
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

    def rollback(self, item_id: str, operator_id: str | None = None) -> MemoryItem | None:
        self.purge_expired()
        history = self.revisions.get(item_id)
        if not history:
            return None
        for item in self.items:
            if item.id == item_id:
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

    def delete(self, item_id: str) -> bool:
        before = len(self.items)
        self.items = [item for item in self.items if item.id != item_id]
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
        self.dsn = (dsn or os.getenv("AGENT_MEMORY_DATABASE_URL") or os.getenv("DATABASE_URL") or self._spring_datasource_dsn() or "").strip()
        self.pool: asyncpg.Pool | None = None

    @property
    def enabled(self) -> bool:
        return bool(self.dsn)

    async def connect(self) -> None:
        if not self.enabled or self.pool is not None:
            return
        self.pool = await asyncpg.create_pool(
            dsn=self.dsn,
            min_size=1,
            max_size=int(os.getenv("AGENT_MEMORY_DB_POOL_SIZE", "5")),
            command_timeout=float(os.getenv("AGENT_MEMORY_DB_CMD_TIMEOUT", "5")),
        )
        await self.init_schema()

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
                    scope TEXT NOT NULL,
                    content TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'task',
                    operator_id TEXT,
                    version INTEGER NOT NULL DEFAULT 1,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ,
                    expires_at TIMESTAMPTZ
                )
                """
            )
            await conn.execute("ALTER TABLE agent_memory_items ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ")
            await conn.execute("ALTER TABLE agent_memory_items ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ")
            await conn.execute("ALTER TABLE agent_memory_items ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'task'")
            await conn.execute("ALTER TABLE agent_memory_items ADD COLUMN IF NOT EXISTS operator_id TEXT")
            await conn.execute("ALTER TABLE agent_memory_items ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1")
            await conn.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_memory_revisions (
                    id BIGSERIAL PRIMARY KEY,
                    memory_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    operator_id TEXT,
                    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """
            )
            await conn.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_agent_memory_revisions_memory_id
                ON agent_memory_revisions (memory_id, version DESC)
                """
            )
            await conn.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_agent_memory_items_scope_created_at
                ON agent_memory_items (scope, created_at DESC)
                """
            )
            await conn.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_agent_memory_items_content_lower
                ON agent_memory_items (LOWER(content) text_pattern_ops)
                """
            )

    async def add(
        self,
        scope: str,
        content: str,
        ttl_seconds: int | None = None,
        kind: str | None = None,
        operator_id: str | None = None,
    ) -> MemoryItem:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        item_id = f"mem_{uuid4().hex[:12]}"
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_memory_items (id, scope, content, kind, operator_id, expires_at)
                VALUES ($1, $2, $3, $4, $5, CASE WHEN $6::int IS NULL THEN NULL ELSE NOW() + ($6::int * INTERVAL '1 second') END)
                RETURNING id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                """,
                item_id,
                scope,
                content,
                normalize_kind(kind),
                operator_id,
                ttl_seconds,
            )
        return self._row_to_item(row)

    async def search(self, query: str, scope: str | None = None) -> list[MemoryItem]:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        terms = significant_terms(query)
        patterns = [f"%{term}%" for term in terms]
        pool_size = _search_pool()
        async with self.pool.acquire() as conn:
            if patterns:
                # 候选召回：命中任一显著词即纳入，后续在 Python 内做相关性重排。
                if scope is None:
                    rows = await conn.fetch(
                        """
                        SELECT id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                        FROM agent_memory_items
                        WHERE LOWER(content) ILIKE ANY($1::text[]) AND (expires_at IS NULL OR expires_at > NOW())
                        ORDER BY created_at DESC
                        LIMIT $2
                        """,
                        patterns,
                        pool_size,
                    )
                else:
                    rows = await conn.fetch(
                        """
                        SELECT id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                        FROM agent_memory_items
                        WHERE scope = $1 AND LOWER(content) ILIKE ANY($2::text[]) AND (expires_at IS NULL OR expires_at > NOW())
                        ORDER BY created_at DESC
                        LIMIT $3
                        """,
                        scope,
                        patterns,
                        pool_size,
                    )
            else:
                # 无显著词：退化为按时间召回。
                if scope is None:
                    rows = await conn.fetch(
                        """
                        SELECT id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                        FROM agent_memory_items
                        WHERE (expires_at IS NULL OR expires_at > NOW())
                        ORDER BY created_at DESC
                        LIMIT $1
                        """,
                        pool_size,
                    )
                else:
                    rows = await conn.fetch(
                        """
                        SELECT id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                        FROM agent_memory_items
                        WHERE scope = $1 AND (expires_at IS NULL OR expires_at > NOW())
                        ORDER BY created_at DESC
                        LIMIT $2
                        """,
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
    ) -> MemoryItem | None:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                current = await conn.fetchrow(
                    """
                    SELECT id, content, version, operator_id, updated_at, created_at
                    FROM agent_memory_items
                    WHERE id = $1 AND (expires_at IS NULL OR expires_at > NOW())
                    FOR UPDATE
                    """,
                    item_id,
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
                    SET content = $2,
                        updated_at = NOW(),
                        version = version + 1,
                        operator_id = COALESCE($3, operator_id),
                        expires_at = CASE WHEN $4::int IS NULL THEN expires_at ELSE NOW() + ($4::int * INTERVAL '1 second') END
                    WHERE id = $1
                    RETURNING id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                    """,
                    item_id,
                    content,
                    operator_id,
                    ttl_seconds,
                )
        return self._row_to_item(row) if row else None

    async def rollback(self, item_id: str, operator_id: str | None = None) -> MemoryItem | None:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                revision = await conn.fetchrow(
                    """
                    SELECT id, content FROM agent_memory_revisions
                    WHERE memory_id = $1
                    ORDER BY version DESC
                    LIMIT 1
                    """,
                    item_id,
                )
                if revision is None:
                    return None
                row = await conn.fetchrow(
                    """
                    UPDATE agent_memory_items
                    SET content = $2,
                        updated_at = NOW(),
                        version = version + 1,
                        operator_id = COALESCE($3, operator_id)
                    WHERE id = $1 AND (expires_at IS NULL OR expires_at > NOW())
                    RETURNING id, scope, content, kind, operator_id, version, created_at, updated_at, expires_at
                    """,
                    item_id,
                    revision["content"],
                    operator_id,
                )
                if row is None:
                    return None
                await conn.execute("DELETE FROM agent_memory_revisions WHERE id = $1", revision["id"])
        return self._row_to_item(row)

    async def delete(self, item_id: str) -> bool:
        if self.pool is None:
            raise RuntimeError("PostgreSQL memory store 未连接")
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute("DELETE FROM agent_memory_revisions WHERE memory_id = $1", item_id)
                result = await conn.execute("DELETE FROM agent_memory_items WHERE id = $1", item_id)
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
                result = await conn.execute("DELETE FROM agent_memory_items WHERE expires_at IS NOT NULL AND expires_at <= NOW()")
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
            kind=row["kind"] if "kind" in row else DEFAULT_MEMORY_KIND,
            operator_id=row["operator_id"] if "operator_id" in row else None,
            version=row["version"] if "version" in row else 1,
            updated_at=iso(row["updated_at"]),
            expires_at=iso(row["expires_at"]),
        )
