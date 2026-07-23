from pathlib import Path
from typing import Any, Dict, Optional
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

from app.api.responses import success
from app.core.agent.executor import AgentExecutor
from app.core.common.constants import PermissionMode
from app.core.common.settings import reload_settings, settings
from app.core.tool.base import ToolExecutionContext
from app.core.tool.mcp_adapter import register_mcp_tools
from app.models.schemas import ToolCall
from app.tools_builtin import register_missing_builtin_tools

router = APIRouter(prefix="/v1/runtime", tags=["runtime"])
_executor: Optional[AgentExecutor] = None


def get_executor() -> AgentExecutor:
    """对外暴露 Executor 单例，用于运行 Agent 和启动期注册外部工具。

    Executor 必须懒加载，避免模块 import 时在环境变量/.env 还没准备好之前固化配置。
    """

    global _executor
    if _executor is None:
        _executor = AgentExecutor()
    return _executor


def reset_executor() -> AgentExecutor:
    """配置重载后重建 Executor，确保 LLM Client、Profile、工具配置同步刷新。"""

    global _executor
    _executor = AgentExecutor()
    return _executor


class ToolInvokeRequest(BaseModel):
    arguments: Dict[str, Any] = Field(default_factory=dict)
    session_id: Optional[str] = None
    run_id: Optional[str] = None
    workspace_dir: Optional[str] = None


@router.get("/tools")
async def list_tools():
    executor = get_executor()
    return success([item.model_dump() for item in executor.registry.list_definitions()])


@router.post("/mcp/reload")
async def reload_mcp_tools():
    """重新探测并注册 MCP 工具。"""

    executor = get_executor()
    registered = await register_mcp_tools(executor.registry, settings.config.mcp)
    return success({"registered": registered, "tools": executor.registry.names()})


@router.post("/tools/{name}/invoke")
async def invoke_tool(
    name: str,
    request: ToolInvokeRequest,
    x_tenant_id: Optional[str] = Header(default=None),
    x_operator_id: Optional[str] = Header(default=None),
):
    """直接调用指定工具，绕过 Agent Loop。供上游 backend 在意图路由后直接派发工具用。

    用于已经精确知道要做什么的场景，例如调用 resume_match 等。
    高风险破坏性工具同时受工具权限元信息和调用上下文约束。
    """

    executor = get_executor()
    tool = executor.registry.get(name)
    if tool is None:
        register_missing_builtin_tools(executor.registry)
        tool = executor.registry.get(name)
    if tool is None or not tool.is_enabled():
        raise HTTPException(status_code=404, detail=f"工具未找到或已禁用: {name}")

    session_id = request.session_id or f"direct_{uuid4().hex[:12]}"
    run_id = request.run_id or f"run_{uuid4().hex[:12]}"
    trace_id = f"trace_{uuid4().hex[:12]}"
    workspace_dir = _configured_workspace_dir()

    tool_call = ToolCall(id=f"call_{uuid4().hex[:8]}", name=name, arguments=request.arguments or {})
    metadata: Dict[str, Any] = {}
    if request.workspace_dir:
        metadata["requested_workspace_dir"] = request.workspace_dir
    if x_tenant_id and x_tenant_id.strip():
        metadata["tenant_id"] = x_tenant_id.strip()
    if x_operator_id and x_operator_id.strip():
        metadata["operator_id"] = x_operator_id.strip()
        metadata["user_id"] = x_operator_id.strip()

    context = ToolExecutionContext(
        run_id=run_id,
        trace_id=trace_id,
        session_id=session_id,
        workspace_dir=workspace_dir,
        metadata=metadata,
    )
    gateway_result = await executor.tool_gateway.execute(
        tool_call,
        _default_permission_mode(),
        context,
        transcript_messages=[],
    )
    result = gateway_result.result
    if result.metadata.get("permission_denied"):
        raise HTTPException(status_code=403, detail=result.error or "工具权限被拒绝")
    return success(result.model_dump())


@router.post("/tools/reload-builtins")
async def reload_builtin_tools():
    executor = get_executor()
    registered = register_missing_builtin_tools(executor.registry)
    return success({"registered": registered, "tools": executor.registry.names()})


@router.get("/config")
async def get_runtime_config():
    return success(_safe_config_payload())


@router.post("/config/reload")
async def reload_runtime_config(config_path: Optional[str] = None):
    """重载 Runtime 配置并重建 Executor。"""

    reload_settings(config_path)
    reset_executor()
    return success(_safe_config_payload())


@router.get("/trace-events")
async def list_trace_events(run_id: str = None):
    executor = get_executor()
    if run_id:
        events = executor.trace_recorder.list_by_run(run_id)
    else:
        events = executor.trace_recorder.events
    return success([item.model_dump() for item in events])


@router.get("/checkpoints")
async def list_checkpoints(session_id: str, run_id: Optional[str] = None):
    """查询会话检查点：默认返回快照元信息列表，带 run_id 时返回该 run 最近一次完整检查点。"""

    executor = get_executor()
    store = executor.checkpoint_store
    if run_id:
        data = await store.load_latest_by_run(session_id, run_id)
    else:
        data = await store.list_snapshots(session_id)
    return success(data)


def _safe_config_payload() -> Dict[str, Any]:
    config = settings.config.model_dump()
    api_key = config.get("llm_service", {}).get("api_key")
    if api_key:
        config["llm_service"]["api_key"] = _mask_secret(api_key)
    bocha_api_key = config.get("web_search", {}).get("bocha_api_key")
    if bocha_api_key:
        config["web_search"]["bocha_api_key"] = _mask_secret(bocha_api_key)
    return {"config_path": settings.config_path, "config": config}


def _mask_secret(value: str) -> str:
    if len(value) <= 8:
        return "****"
    return f"{value[:4]}****{value[-4:]}"


def _configured_workspace_dir() -> str:
    return str(Path(settings.workspace_dir or ".").expanduser().resolve())


def _default_permission_mode() -> PermissionMode:
    try:
        return PermissionMode(settings.config.permission.default_mode or PermissionMode.DEFAULT.value)
    except ValueError:
        return PermissionMode.DEFAULT
