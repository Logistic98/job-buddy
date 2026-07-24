from contextlib import asynccontextmanager

from fastapi import FastAPI, Header
from loguru import logger
from pydantic import BaseModel, Field

from .env import load_root_dotenv
from .internal_auth import install_internal_auth
from .store import MEMORY_KINDS, MemoryStore, PostgresMemoryStore, normalize_kind
from .tencentdb_adapter import TencentDBMemoryAdapter

load_root_dotenv()

local_store = MemoryStore()
postgres_store = PostgresMemoryStore()
adapter = TencentDBMemoryAdapter()

DEFAULT_TENANT = "default-tenant"
ANONYMOUS_OPERATOR = "anonymous"


@asynccontextmanager
async def lifespan(app: FastAPI):
    if postgres_store.enabled:
        await postgres_store.connect()
    try:
        yield
    finally:
        await postgres_store.close()


app = FastAPI(title="agent-memory", version="1.0.0", lifespan=lifespan)
install_internal_auth(app)


class MemoryCreateRequest(BaseModel):
    scope: str = "session"
    content: str = Field(min_length=1)
    role: str = "user"
    kind: str | None = None
    category: str = "preference"
    source: str = "agent-memory"
    enabled: bool = True
    operator_id: str | None = None
    ttl_seconds: int | None = Field(default=None, ge=1)


class MemoryUpdateRequest(BaseModel):
    content: str = Field(min_length=1)
    operator_id: str | None = None
    ttl_seconds: int | None = Field(default=None, ge=1)


class OperatorRequest(BaseModel):
    operator_id: str | None = None


def _resolve_identity(header_tenant: str | None, header_operator: str | None) -> tuple[str, str]:
    """Resolve identity only from trusted headers injected by the authenticated upstream service."""
    tenant_id = (header_tenant or DEFAULT_TENANT).strip() or DEFAULT_TENANT
    operator_id = (header_operator or ANONYMOUS_OPERATOR).strip() or ANONYMOUS_OPERATOR
    return tenant_id, operator_id


def _owned_scope(tenant_id: str, operator_id: str, scope: str) -> str:
    return f"{tenant_id}:{operator_id}:{scope}"


def _audit(action: str, operator_id: str, *, outcome: str, **fields) -> None:
    """记忆操作审计落日志，保证写入/召回/删除/回滚六个环节可追溯。"""
    logger.bind(audit="memory", action=action, operator_id=operator_id, outcome=outcome, **fields).info(
        "memory_audit action={} operator={} outcome={}", action, operator_id, outcome
    )


async def add_local_memory(
    scope: str,
    content: str,
    ttl_seconds: int | None = None,
    kind: str | None = None,
    category: str | None = None,
    source: str | None = None,
    enabled: bool = True,
    operator_id: str | None = None,
    tenant_id: str = DEFAULT_TENANT,
) -> dict:
    if postgres_store.enabled:
        item = await postgres_store.add(
            scope,
            content,
            ttl_seconds,
            kind,
            category,
            source,
            enabled,
            operator_id,
            tenant_id,
        )
    else:
        item = local_store.add(
            scope,
            content,
            ttl_seconds,
            kind,
            category,
            source,
            enabled,
            operator_id,
            tenant_id,
        )
    return item.__dict__


async def search_local_memories(query: str, scope: str | None, *, tenant_id: str, operator_id: str) -> list[dict]:
    if postgres_store.enabled:
        items = await postgres_store.search(query, scope, tenant_id=tenant_id, operator_id=operator_id)
    else:
        items = await local_store.search(query, scope, tenant_id=tenant_id, operator_id=operator_id)
    return [item.__dict__ for item in items]


async def list_local_memories(scope: str | None, limit: int, *, tenant_id: str, operator_id: str) -> list[dict]:
    if postgres_store.enabled:
        items = await postgres_store.list_items(scope, tenant_id=tenant_id, operator_id=operator_id, limit=limit)
    else:
        items = local_store.list_items(scope, tenant_id=tenant_id, operator_id=operator_id, limit=limit)
    return [item.__dict__ for item in items]


def local_backend_status() -> dict:
    if postgres_store.enabled:
        return {"backend": "postgresql", "database_configured": True}
    return {"backend": "local-memory", "items": len(local_store.items)}


@app.get("/health")
async def health() -> dict:
    if not adapter.enabled:
        return {
            "code": 200,
            "message": "success",
            "data": {
                "status": "UP",
                "service": "agent-memory",
                "memory_kinds": list(MEMORY_KINDS),
                **local_backend_status(),
            },
        }
    try:
        gateway = await adapter.health()
        return {
            "code": 200,
            "message": "success",
            "data": {
                "status": "UP",
                "service": "agent-memory",
                "backend": "tencentdb-agent-memory",
                "gateway": gateway,
            },
        }
    except Exception as exc:
        return {
            "code": 200,
            "message": "success",
            "data": {"status": "DEGRADED", "service": "agent-memory", "reason": str(exc), **local_backend_status()},
        }


@app.post("/v1/memories")
async def create_memory(
    request: MemoryCreateRequest,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    kind = normalize_kind(request.kind)
    if request.scope != "long_term" and adapter.is_available():
        try:
            data = await adapter.capture(
                _owned_scope(tenant_id, operator_id, request.scope), request.content, request.role
            )
            _audit("create", operator_id, outcome="gateway", tenant_id=tenant_id, scope=request.scope, kind=kind)
            return {"code": 200, "message": "success", "data": data}
        except Exception as exc:
            logger.warning("memory capture 网关失败，降级本地存储: scope={}, error={}", request.scope, exc)
    data = await add_local_memory(
        request.scope,
        request.content,
        request.ttl_seconds,
        kind,
        request.category,
        request.source,
        request.enabled,
        operator_id,
        tenant_id,
    )
    _audit(
        "create",
        operator_id,
        outcome="local",
        tenant_id=tenant_id,
        scope=request.scope,
        kind=kind,
        memory_id=data.get("id"),
    )
    return {"code": 200, "message": "success", "data": data}


@app.get("/v1/memories")
async def list_memories(
    scope: str | None = None,
    limit: int = 1000,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """按当前租户和操作者列出记忆，供受鉴权的管理界面使用。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    bounded_limit = max(1, min(limit, 1000))
    data = await list_local_memories(scope, bounded_limit, tenant_id=tenant_id, operator_id=operator_id)
    _audit("list", operator_id, outcome="local", tenant_id=tenant_id, scope=scope, hits=len(data))
    return {"code": 200, "message": "success", "data": data}


@app.get("/v1/memories/search")
async def search_memories(
    q: str,
    scope: str | None = None,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    if scope != "long_term" and adapter.is_available():
        try:
            data = await adapter.recall(q, _owned_scope(tenant_id, operator_id, scope or "session"))
            _audit(
                "recall",
                operator_id,
                outcome="gateway",
                tenant_id=tenant_id,
                scope=scope,
                hits=len(data) if isinstance(data, list) else None,
            )
            return {"code": 200, "message": "success", "data": data}
        except Exception as exc:
            logger.warning("memory recall 网关失败，降级本地检索: scope={}, error={}", scope, exc)
    data = await search_local_memories(q, scope, tenant_id=tenant_id, operator_id=operator_id)
    _audit("recall", operator_id, outcome="local", tenant_id=tenant_id, scope=scope, hits=len(data))
    return {"code": 200, "message": "success", "data": data}


@app.put("/v1/memories/{memory_id}")
async def update_memory(
    memory_id: str,
    request: MemoryUpdateRequest,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """更新当前租户和操作者拥有的本地记忆内容，可同时刷新 TTL。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    if postgres_store.enabled:
        item = await postgres_store.update(memory_id, request.content, request.ttl_seconds, operator_id, tenant_id)
    else:
        item = local_store.update(memory_id, request.content, request.ttl_seconds, operator_id, tenant_id)
    if item is None:
        _audit("update", operator_id, outcome="not_found", memory_id=memory_id)
        logger.info("memory update 未命中: memory_id={}", memory_id)
        return {"code": 1, "message": f"memory not found: {memory_id}", "data": None}
    _audit("update", operator_id, outcome="ok", memory_id=memory_id, version=item.version)
    return {"code": 200, "message": "success", "data": item.__dict__}


@app.post("/v1/memories/{memory_id}/rollback")
async def rollback_memory(
    memory_id: str,
    request: OperatorRequest | None = None,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """回滚当前租户和操作者拥有的记忆到上一版本。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    if postgres_store.enabled:
        item = await postgres_store.rollback(memory_id, operator_id, tenant_id)
    else:
        item = local_store.rollback(memory_id, operator_id, tenant_id)
    if item is None:
        _audit("rollback", operator_id, outcome="no_revision", memory_id=memory_id)
        logger.info("memory rollback 无可回滚版本: memory_id={}", memory_id)
        return {"code": 1, "message": f"no revision to rollback: {memory_id}", "data": None}
    _audit("rollback", operator_id, outcome="ok", memory_id=memory_id, version=item.version)
    return {"code": 200, "message": "success", "data": item.__dict__}


@app.delete("/v1/memories")
async def clear_memories(
    scope: str | None = None,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """清空当前租户和操作者拥有的记忆，可按 scope 收窄。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    if postgres_store.enabled:
        deleted = await postgres_store.clear(tenant_id=tenant_id, operator_id=operator_id, scope=scope)
    else:
        deleted = local_store.clear(tenant_id=tenant_id, operator_id=operator_id, scope=scope)
    _audit("clear", operator_id, outcome="ok", tenant_id=tenant_id, scope=scope, deleted=deleted)
    return {"code": 200, "message": "success", "data": {"deleted": deleted}}


@app.delete("/v1/memories/{memory_id}")
async def delete_memory(
    memory_id: str,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """删除当前租户和操作者拥有的本地记忆。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    if postgres_store.enabled:
        deleted = await postgres_store.delete(memory_id, tenant_id=tenant_id, operator_id=operator_id)
    else:
        deleted = local_store.delete(memory_id, tenant_id=tenant_id, operator_id=operator_id)
    if not deleted:
        _audit("delete", operator_id, outcome="not_found", memory_id=memory_id)
        logger.info("memory delete 未命中: memory_id={}", memory_id)
        return {"code": 1, "message": f"memory not found: {memory_id}", "data": None}
    _audit("delete", operator_id, outcome="ok", memory_id=memory_id)
    return {"code": 200, "message": "success", "data": {"id": memory_id, "deleted": True}}


@app.post("/v1/memories/purge-expired")
async def purge_expired_memories(
    x_tenant_id: str | None = Header(default=None), x_operator_id: str | None = Header(default=None)
) -> dict:
    """清理已过期记忆，供受信任的定时任务或运维触发。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    if postgres_store.enabled:
        purged = await postgres_store.purge_expired()
    else:
        purged = local_store.purge_expired()
    _audit("purge", operator_id, outcome="ok", purged=purged)
    return {"code": 200, "message": "success", "data": {"purged": purged}}
