"""Mem0 OSS adapter: ownership and lifecycle policy; no custom retrieval engine."""

import hashlib
import json
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from uuid import UUID

from sqlalchemy import create_engine, delete
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.history import MemoryHistory

MEMORY_KINDS = ("step", "task", "long_term", "semantic")


def normalize_kind(kind: str | None) -> str:
    value = (kind or "").strip().lower()
    return value if value in MEMORY_KINDS else "task"


def owner_key(tenant_id: str, operator_id: str) -> str:
    return hashlib.sha256(json.dumps([tenant_id, operator_id], ensure_ascii=False).encode()).hexdigest()


def database_url() -> str:
    explicit = os.getenv("AGENT_MEMORY_DATABASE_URL", "").strip()
    if explicit:
        return explicit
    url = os.getenv("SPRING_DATASOURCE_URL", "").removeprefix("jdbc:")
    if not url:
        raise ValueError("AGENT_MEMORY_DATABASE_URL is required for Mem0 pgvector")
    user = quote(os.environ["SPRING_DATASOURCE_USERNAME"], safe="")
    password = quote(os.environ["SPRING_DATASOURCE_PASSWORD"], safe="")
    return url.replace("postgresql://", f"postgresql://{user}:{password}@", 1)


def required(name: str, fallback: str = "") -> str:
    value = os.getenv(name, "").strip() or os.getenv(fallback, "").strip()
    if not value or value in {"sk-xxx", "change-me"}:
        raise ValueError(f"{name} must be configured")
    return value


def build_memory(*, vector_config=None, history_path=None):
    # Disable SDK telemetry before importing Mem0, including its import-time setup.
    os.environ["MEM0_TELEMETRY"] = "false"
    history = Path(history_path or os.getenv("AGENT_MEMORY_HISTORY_PATH", "data/mem0/history.db")).resolve()
    history.parent.mkdir(parents=True, exist_ok=True)
    os.environ["MEM0_DIR"] = str(history.parent)
    from mem0 import Memory

    config = {
        "vector_store": vector_config
        or {
            "provider": "pgvector",
            "config": {
                "connection_string": database_url(),
                "collection_name": os.getenv("AGENT_MEMORY_COLLECTION", "agent_memory_mem0"),
                "embedding_model_dims": int(required("AGENT_MEMORY_EMBEDDING_DIMS")),
                "sslmode": os.getenv("AGENT_MEMORY_DB_SSL_MODE", "require"),
                "maxconn": int(os.getenv("AGENT_MEMORY_DB_POOL_SIZE", "5")),
            },
        },
        "history_db_path": str(history),
        "embedder": {
            "provider": "openai",
            "config": {
                "api_key": required("AGENT_MEMORY_EMBEDDING_API_KEY"),
                "model": required("AGENT_MEMORY_EMBEDDING_MODEL"),
                "openai_base_url": required("AGENT_MEMORY_EMBEDDING_BASE_URL").removesuffix("/embeddings"),
            },
        },
        "llm": {
            "provider": "openai",
            "config": {
                "api_key": required("AGENT_MEMORY_LLM_API_KEY", "JOB_BUDDY_LLM_API_KEY"),
                "model": required("AGENT_MEMORY_LLM_MODEL", "JOB_BUDDY_LLM_MODEL_NAME"),
                "openai_base_url": required("AGENT_MEMORY_LLM_BASE_URL", "JOB_BUDDY_LLM_BASE_URL"),
                "temperature": 0,
            },
        },
    }
    if config["vector_store"]["provider"] == "pgvector":
        from psycopg.conninfo import make_conninfo

        config["vector_store"]["config"]["connection_string"] = make_conninfo(
            config["vector_store"]["config"]["connection_string"],
            connect_timeout=int(os.getenv("AGENT_MEMORY_DB_CONNECT_TIMEOUT_SECONDS", "8")),
            options=f"-c statement_timeout={int(float(os.getenv('AGENT_MEMORY_DB_CMD_TIMEOUT', '5')) * 1000)}",
        )
    memory = Memory.from_config(config)
    configure_timeouts(memory)
    return memory


def configure_timeouts(memory):
    memory.embedding_model.client = memory.embedding_model.client.with_options(
        timeout=float(os.getenv("AGENT_MEMORY_EMBEDDING_TIMEOUT_SECONDS", "5")), max_retries=0
    )
    memory.llm.client = memory.llm.client.with_options(
        timeout=float(os.getenv("AGENT_MEMORY_LLM_TIMEOUT_SECONDS", "30")), max_retries=0
    )


class MemoryStore:
    def __init__(self, memory):
        self.memory = memory
        # 复用 SDK 连接，兼容持久化和内存 history；访问时遵守 SDK 的互斥锁。
        self._history_engine = create_engine(
            "sqlite://", creator=lambda: self.memory.db.connection, poolclass=StaticPool
        )
        # ponytail: single-process mutation lock; use distributed leases before scaling replicas.
        self.lock = threading.RLock()

    def _filters(self, tenant_id, operator_id, scope=None, *, active=True):
        filters = {"user_id": owner_key(tenant_id, operator_id)}
        if scope is not None:
            filters["scope"] = scope
        if active:
            filters["expires_epoch"] = {"gt": time.time()}
        return filters

    @staticmethod
    def _item(row):
        meta = row.get("metadata") or {}
        return {
            "id": meta.get("legacy_id") or "mem_" + UUID(row["id"]).hex,
            "content": row["memory"],
            "created_at": row.get("created_at"),
            "updated_at": row.get("updated_at"),
            "tenant_id": meta["tenant_id"],
            "operator_id": meta["operator_id"],
            "scope": meta["scope"],
            "kind": meta.get("kind", "task"),
            "source": meta.get("source", "agent-memory"),
            "enabled": meta.get("enabled", True),
            "version": meta.get("version", 1),
            "expires_at": meta.get("expires_at"),
            "score": row.get("score"),
        }

    def _get_owned(self, item_id, tenant_id, operator_id):
        try:
            raw_id = str(UUID(item_id.removeprefix("mem_"))) if item_id.startswith("mem_") else ""
        except ValueError:
            raw_id = ""
        if raw_id:
            row = self.memory.get(raw_id)
        else:
            filters = self._filters(tenant_id, operator_id)
            filters["legacy_id"] = item_id
            rows = self.memory.get_all(filters=filters, top_k=1)["results"]
            row = rows[0] if rows else None
        if not row or row.get("user_id") != owner_key(tenant_id, operator_id):
            return None
        if row.get("metadata", {}).get("expires_epoch", 0) <= time.time():
            return None
        return row

    def add(
        self,
        scope,
        content,
        ttl_seconds=None,
        kind=None,
        source=None,
        enabled=True,
        operator_id="anonymous",
        tenant_id="default-tenant",
    ):
        expires = time.time() + ttl_seconds if ttl_seconds else 253402300799.0
        metadata = {
            "scope": scope,
            "kind": normalize_kind(kind),
            "source": source or "agent-memory",
            "enabled": enabled,
            "tenant_id": tenant_id,
            "operator_id": operator_id,
            "expires_epoch": expires,
            "expires_at": datetime.fromtimestamp(expires, timezone.utc).isoformat() if ttl_seconds else None,
            "version": 1,
        }
        with self.lock:
            result = self.memory.add(content, user_id=owner_key(tenant_id, operator_id), metadata=metadata, infer=False)
            rows = result["results"]
            if len(rows) != 1 or rows[0].get("event") != "ADD":
                raise RuntimeError("Mem0 did not acknowledge exactly one explicit memory")
            return self._item(self.memory.get(rows[0]["id"]))

    def list_items(self, scope=None, *, tenant_id="default-tenant", operator_id="anonymous", limit=1000):
        rows = self.memory.get_all(filters=self._filters(tenant_id, operator_id, scope), top_k=limit)["results"]
        return [self._item(row) for row in rows]

    def search(self, query, scope=None, *, tenant_id="default-tenant", operator_id="anonymous"):
        if not query.strip():
            return []
        filters = self._filters(tenant_id, operator_id, scope)
        filters["enabled"] = True
        rows = self.memory.search(
            query,
            filters=filters,
            top_k=int(os.getenv("AGENT_MEMORY_SEARCH_TOP_K", "10")),
            threshold=float(os.getenv("AGENT_MEMORY_SEARCH_THRESHOLD", "0.1")),
        )["results"]
        return [self._item(row) for row in rows]

    def update(self, item_id, content, ttl_seconds=None, operator_id="anonymous", tenant_id="default-tenant"):
        with self.lock:
            row = self._get_owned(item_id, tenant_id, operator_id)
            if row is None:
                return None
            metadata = dict(row["metadata"])
            history = sorted(
                self.memory.history(row["id"]),
                key=lambda entry: entry.get("updated_at") or entry["created_at"],
            )
            # IDs reference native Mem0 history; no second copy of memory content.
            stack = metadata.get("rollback_ids", [])
            if history:
                stack = [*stack, history[-1]["id"]]
            metadata.update(version=metadata.get("version", 1) + 1, rollback_ids=stack)
            if ttl_seconds is not None:
                expires = time.time() + ttl_seconds
                metadata.update(
                    expires_epoch=expires, expires_at=datetime.fromtimestamp(expires, timezone.utc).isoformat()
                )
            self.memory.update(row["id"], text=content, metadata=metadata)
            return self._item(self.memory.get(row["id"]))

    def rollback(self, item_id, operator_id="anonymous", tenant_id="default-tenant"):
        with self.lock:
            row = self._get_owned(item_id, tenant_id, operator_id)
            if row is None:
                return None
            metadata = dict(row["metadata"])
            stack = list(metadata.get("rollback_ids", []))
            if not stack:
                return None
            target = stack.pop()
            revision = next((entry for entry in self.memory.history(row["id"]) if entry["id"] == target), None)
            if revision is None:
                raise RuntimeError("Mem0 history revision unavailable")
            metadata.update(rollback_ids=stack, version=metadata.get("version", 1) + 1)
            self.memory.update(row["id"], text=revision["new_memory"], metadata=metadata)
            return self._item(self.memory.get(row["id"]))

    def _delete(self, raw_id):
        self.memory.delete(raw_id)
        # Mem0 delete retains plaintext tombstones; erase them for the service's deletion contract.
        with self.memory.db._lock, Session(self._history_engine) as session, session.begin():
            session.execute(delete(MemoryHistory).where(MemoryHistory.memory_id == raw_id))

    def delete(self, item_id, *, tenant_id="default-tenant", operator_id="anonymous"):
        with self.lock:
            row = self._get_owned(item_id, tenant_id, operator_id)
            if row is None:
                return False
            self._delete(row["id"])
            return True

    def clear(self, *, tenant_id="default-tenant", operator_id="anonymous", scope=None):
        with self.lock:
            filters = self._filters(tenant_id, operator_id, scope, active=False)
            return self._delete_matching(filters)

    def _delete_matching(self, filters):
        count = 0
        while True:
            rows = self.memory.get_all(filters=filters, top_k=1000, show_expired=True)["results"]
            if not rows:
                return count
            for row in rows:
                self._delete(row["id"])
                count += 1

    def purge_expired(self, *, tenant_id="default-tenant", operator_id="anonymous"):
        with self.lock:
            filters = self._filters(tenant_id, operator_id, active=False)
            filters["expires_epoch"] = {"lte": time.time()}
            return self._delete_matching(filters)

    def ensure_ready(self):
        self.memory.get_all(filters={"user_id": "__health_probe__"}, top_k=1)
        self.memory.history("__health_probe__")

    def close(self):
        self._history_engine.dispose()
        self.memory.close()
        vector_store = self.memory.vector_store
        if hasattr(vector_store, "connection_pool"):
            pool = vector_store.connection_pool
            (pool.close if hasattr(pool, "close") else pool.closeall)()
        elif hasattr(vector_store, "client"):
            vector_store.client.close()
