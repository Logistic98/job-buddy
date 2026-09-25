"""基于选定存储提供租户隔离的记忆生命周期接口。"""

from contextlib import asynccontextmanager

from fastapi import FastAPI, Header
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from .env import load_root_dotenv
from .internal_auth import install_internal_auth
from .store import MEMORY_KINDS, MemoryStore, build_memory, normalize_kind

load_root_dotenv()

store: MemoryStore | None = None

DEFAULT_TENANT = "default-tenant"
ANONYMOUS_OPERATOR = "anonymous"


@asynccontextmanager
async def lifespan(app: FastAPI):
    global store
    store = await run_in_threadpool(lambda: MemoryStore(build_memory()))
    try:
        yield
    finally:
        await run_in_threadpool(store.close)
        store = None


app = FastAPI(title="agent-memory", version="1.0.0", lifespan=lifespan)
install_internal_auth(app)


async def invoke(method: str, *args, **kwargs):
    try:
        if store is None:
            raise RuntimeError("Mem0 is not initialized")
        return await run_in_threadpool(getattr(store, method), *args, **kwargs)
    except Exception as exc:
        logger.error("memory operation failed: method={} error_type={}", method, type(exc).__name__)
        from fastapi import HTTPException

        raise HTTPException(status_code=503, detail="Mem0 operation unavailable") from None


@app.exception_handler(503)
async def unavailable(request, exc):
    return JSONResponse(status_code=503, content={"code": 503, "message": "Mem0 operation unavailable", "data": None})


class MemoryCreateRequest(BaseModel):
    scope: str = "session"
    content: str = Field(min_length=1)
    role: str = "user"
    kind: str | None = None
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
    """仅从已认证上游注入的可信请求头解析身份。"""
    tenant_id = (header_tenant or DEFAULT_TENANT).strip() or DEFAULT_TENANT
    operator_id = (header_operator or ANONYMOUS_OPERATOR).strip() or ANONYMOUS_OPERATOR
    return tenant_id, operator_id


def _audit(action: str, operator_id: str, *, outcome: str, **fields) -> None:
    """记忆操作审计落日志，保证写入/召回/删除/回滚六个环节可追溯。"""
    logger.bind(audit="memory", action=action, operator_id=operator_id, outcome=outcome, **fields).info(
        "memory_audit action={} operator={} outcome={}", action, operator_id, outcome
    )


@app.get("/health")
async def health():
    try:
        await invoke("ensure_ready")
    except Exception:
        return JSONResponse(
            status_code=503,
            content={
                "code": 503,
                "message": "Mem0 unavailable",
                "data": {"status": "DOWN", "service": "agent-memory", "backend": "mem0"},
            },
        )
    return {
        "code": 200,
        "message": "success",
        "data": {
            "status": "UP",
            "service": "agent-memory",
            "backend": "mem0",
            "memory_kinds": list(MEMORY_KINDS),
        },
    }


@app.post("/v1/memories")
async def create_memory(
    request: MemoryCreateRequest,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    kind = normalize_kind(request.kind)
    data = await invoke(
        "add",
        request.scope,
        request.content,
        request.ttl_seconds,
        kind,
        request.source,
        request.enabled,
        operator_id,
        tenant_id,
    )
    _audit(
        "create",
        operator_id,
        outcome="mem0",
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
    data = await invoke("list_items", scope, limit=bounded_limit, tenant_id=tenant_id, operator_id=operator_id)
    _audit("list", operator_id, outcome="mem0", tenant_id=tenant_id, scope=scope, hits=len(data))
    return {"code": 200, "message": "success", "data": data}


@app.get("/v1/memories/search")
async def search_memories(
    q: str,
    scope: str | None = None,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    data = await invoke("search", q, scope, tenant_id=tenant_id, operator_id=operator_id)
    _audit("recall", operator_id, outcome="mem0", tenant_id=tenant_id, scope=scope, hits=len(data))
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
    item = await invoke("update", memory_id, request.content, request.ttl_seconds, operator_id, tenant_id)
    if item is None:
        _audit("update", operator_id, outcome="not_found", memory_id=memory_id)
        logger.info("memory update 未命中: memory_id={}", memory_id)
        return JSONResponse(status_code=404, content={"code": 404, "message": "memory not found", "data": None})
    _audit("update", operator_id, outcome="ok", memory_id=memory_id, version=item["version"])
    return {"code": 200, "message": "success", "data": item}


@app.post("/v1/memories/{memory_id}/rollback")
async def rollback_memory(
    memory_id: str,
    request: OperatorRequest | None = None,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """回滚当前租户和操作者拥有的记忆到上一版本。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    item = await invoke("rollback", memory_id, operator_id, tenant_id)
    if item is None:
        _audit("rollback", operator_id, outcome="no_revision", memory_id=memory_id)
        logger.info("memory rollback 无可回滚版本: memory_id={}", memory_id)
        return JSONResponse(status_code=404, content={"code": 404, "message": "no revision to rollback", "data": None})
    _audit("rollback", operator_id, outcome="ok", memory_id=memory_id, version=item["version"])
    return {"code": 200, "message": "success", "data": item}


@app.delete("/v1/memories")
async def clear_memories(
    scope: str | None = None,
    x_tenant_id: str | None = Header(default=None),
    x_operator_id: str | None = Header(default=None),
) -> dict:
    """清空当前租户和操作者拥有的记忆，可按 scope 收窄。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    deleted = await invoke("clear", tenant_id=tenant_id, operator_id=operator_id, scope=scope)
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
    deleted = await invoke("delete", memory_id, tenant_id=tenant_id, operator_id=operator_id)
    if not deleted:
        _audit("delete", operator_id, outcome="not_found", memory_id=memory_id)
        logger.info("memory delete 未命中: memory_id={}", memory_id)
        return JSONResponse(status_code=404, content={"code": 404, "message": "memory not found", "data": None})
    _audit("delete", operator_id, outcome="ok", memory_id=memory_id)
    return {"code": 200, "message": "success", "data": {"id": memory_id, "deleted": True}}


@app.post("/v1/memories/purge-expired")
async def purge_expired_memories(
    x_tenant_id: str | None = Header(default=None), x_operator_id: str | None = Header(default=None)
) -> dict:
    """清理已过期记忆，供受信任的定时任务或运维触发。"""
    tenant_id, operator_id = _resolve_identity(x_tenant_id, x_operator_id)
    purged = await invoke("purge_expired", tenant_id=tenant_id, operator_id=operator_id)
    _audit("purge", operator_id, outcome="ok", purged=purged)
    return {"code": 200, "message": "success", "data": {"purged": purged}}
