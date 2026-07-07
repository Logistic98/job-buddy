from contextlib import asynccontextmanager

from fastapi import FastAPI, Header
from loguru import logger
from pydantic import BaseModel, Field

from .env import load_root_dotenv
from .store import MEMORY_KINDS, MemoryStore, PostgresMemoryStore, normalize_kind
from .tencentdb_adapter import TencentDBMemoryAdapter

load_root_dotenv()

local_store = MemoryStore()
postgres_store = PostgresMemoryStore()
adapter = TencentDBMemoryAdapter()

# 缺省操作者标识：调用方未带 X-Operator-Id 头且请求体未指定时使用，保证审计字段不为空。
ANONYMOUS_OPERATOR = "anonymous"


@asynccontextmanager
async def lifespan(app: FastAPI):
    if postgres_store.enabled:
        await postgres_store.connect()
    try:
        yield
    finally:
        await postgres_store.close()


app = FastAPI(title="agent-memory", version="0.4.0", lifespan=lifespan)


class MemoryCreateRequest(BaseModel):
    scope: str = "session"
    content: str = Field(min_length=1)
    role: str = "user"
    kind: str | None = None
    operator_id: str | None = None
    ttl_seconds: int | None = Field(default=None, ge=1)


class MemoryUpdateRequest(BaseModel):
    content: str = Field(min_length=1)
    operator_id: str | None = None
    ttl_seconds: int | None = Field(default=None, ge=1)


class OperatorRequest(BaseModel):
    operator_id: str | None = None


def _resolve_operator(header_operator: str | None, body_operator: str | None) -> str:
    """记忆是攻击面：写入/召回/删除/回滚都必须带可审计的操作者标识。

    优先取请求头 X-Operator-Id（由 BFF/网关注入），其次取请求体声明，最后回落匿名。
    真正的身份鉴别由上游网关负责，这里负责把操作者贯穿到存储与审计日志。
    """
    for candidate in (header_operator, body_operator):
        if candidate and candidate.strip():
            return candidate.strip()
    return ANONYMOUS_OPERATOR


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
    operator_id: str | None = None,
) -> dict:
    if postgres_store.enabled:
        item = await postgres_store.add(scope, content, ttl_seconds, kind, operator_id)
    else:
        item = local_store.add(scope, content, ttl_seconds, kind, operator_id)
    return item.__dict__


async def search_local_memories(query: str, scope: str | None = None) -> list[dict]:
    if postgres_store.enabled:
        items = await postgres_store.search(query, scope)
    else:
        items = await local_store.search(query, scope)
    return [item.__dict__ for item in items]


def local_backend_status() -> dict:
    if postgres_store.enabled:
        return {"backend": "postgresql", "database_configured": True}
    return {"backend": "local-memory", "items": len(local_store.items)}


@app.get("/health")
async def health() -> dict:
    if not adapter.enabled:
        return {"code": 200, "message": "success", "data": {"status": "UP", "service": "agent-memory", "memory_kinds": list(MEMORY_KINDS), **local_backend_status()}}
    try:
        gateway = await adapter.health()
        return {"code": 200, "message": "success", "data": {"status": "UP", "service": "agent-memory", "backend": "tencentdb-agent-memory", "gateway": gateway}}
    except Exception as exc:
        return {"code": 200, "message": "success", "data": {"status": "DEGRADED", "service": "agent-memory", "reason": str(exc), **local_backend_status()}}


@app.post("/v1/memories")
async def create_memory(request: MemoryCreateRequest, x_operator_id: str | None = Header(default=None)) -> dict:
    operator_id = _resolve_operator(x_operator_id, request.operator_id)
    kind = normalize_kind(request.kind)
    if adapter.is_available():
        try:
            data = await adapter.capture(request.scope, request.content, request.role)
            _audit("create", operator_id, outcome="gateway", scope=request.scope, kind=kind)
            return {"code": 200, "message": "success", "data": data}
        except Exception as exc:
            logger.warning("memory capture 网关失败，降级本地存储: scope={}, error={}", request.scope, exc)
    data = await add_local_memory(request.scope, request.content, request.ttl_seconds, kind, operator_id)
    _audit("create", operator_id, outcome="local", scope=request.scope, kind=kind, memory_id=data.get("id"))
    return {"code": 200, "message": "success", "data": data}


@app.get("/v1/memories/search")
async def search_memories(q: str, scope: str | None = None, x_operator_id: str | None = Header(default=None)) -> dict:
    operator_id = _resolve_operator(x_operator_id, None)
    if adapter.is_available():
        try:
            data = await adapter.recall(q, scope or "session")
            _audit("recall", operator_id, outcome="gateway", scope=scope, hits=len(data) if isinstance(data, list) else None)
            return {"code": 200, "message": "success", "data": data}
        except Exception as exc:
            logger.warning("memory recall 网关失败，降级本地检索: scope={}, error={}", scope, exc)
    data = await search_local_memories(q, scope)
    _audit("recall", operator_id, outcome="local", scope=scope, hits=len(data))
    return {"code": 200, "message": "success", "data": data}


@app.put("/v1/memories/{memory_id}")
async def update_memory(memory_id: str, request: MemoryUpdateRequest, x_operator_id: str | None = Header(default=None)) -> dict:
    """更新本地后端记忆内容，可同时刷新 TTL。网关记忆不支持更新，仅作用于本地存储。"""
    operator_id = _resolve_operator(x_operator_id, request.operator_id)
    if postgres_store.enabled:
        item = await postgres_store.update(memory_id, request.content, request.ttl_seconds, operator_id)
    else:
        item = local_store.update(memory_id, request.content, request.ttl_seconds, operator_id)
    if item is None:
        _audit("update", operator_id, outcome="not_found", memory_id=memory_id)
        logger.info("memory update 未命中: memory_id={}", memory_id)
        return {"code": 1, "message": f"memory not found: {memory_id}", "data": None}
    _audit("update", operator_id, outcome="ok", memory_id=memory_id, version=item.version)
    return {"code": 200, "message": "success", "data": item.__dict__}


@app.post("/v1/memories/{memory_id}/rollback")
async def rollback_memory(memory_id: str, request: OperatorRequest | None = None, x_operator_id: str | None = Header(default=None)) -> dict:
    """回滚到上一版本内容。无历史版本时返回错误码，仅作用于本地存储。"""
    operator_id = _resolve_operator(x_operator_id, request.operator_id if request else None)
    if postgres_store.enabled:
        item = await postgres_store.rollback(memory_id, operator_id)
    else:
        item = local_store.rollback(memory_id, operator_id)
    if item is None:
        _audit("rollback", operator_id, outcome="no_revision", memory_id=memory_id)
        logger.info("memory rollback 无可回滚版本: memory_id={}", memory_id)
        return {"code": 1, "message": f"no revision to rollback: {memory_id}", "data": None}
    _audit("rollback", operator_id, outcome="ok", memory_id=memory_id, version=item.version)
    return {"code": 200, "message": "success", "data": item.__dict__}


@app.delete("/v1/memories/{memory_id}")
async def delete_memory(memory_id: str, x_operator_id: str | None = Header(default=None)) -> dict:
    """删除本地后端记忆。网关记忆不支持删除，仅作用于本地存储。"""
    operator_id = _resolve_operator(x_operator_id, None)
    if postgres_store.enabled:
        deleted = await postgres_store.delete(memory_id)
    else:
        deleted = local_store.delete(memory_id)
    if not deleted:
        _audit("delete", operator_id, outcome="not_found", memory_id=memory_id)
        logger.info("memory delete 未命中: memory_id={}", memory_id)
        return {"code": 1, "message": f"memory not found: {memory_id}", "data": None}
    _audit("delete", operator_id, outcome="ok", memory_id=memory_id)
    return {"code": 200, "message": "success", "data": {"id": memory_id, "deleted": True}}


@app.post("/v1/memories/purge-expired")
async def purge_expired_memories(x_operator_id: str | None = Header(default=None)) -> dict:
    """清理已过期记忆，供定时任务或运维触发。"""
    operator_id = _resolve_operator(x_operator_id, None)
    if postgres_store.enabled:
        purged = await postgres_store.purge_expired()
    else:
        purged = local_store.purge_expired()
    _audit("purge", operator_id, outcome="ok", purged=purged)
    return {"code": 200, "message": "success", "data": {"purged": purged}}
