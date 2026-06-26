import time
from uuid import uuid4

from fastapi import FastAPI
from loguru import logger

from .models import ToolError, ToolExecuteRequest, ToolResult
from .registry import get_tool, list_tools
from .tools import TOOL_EXECUTORS

app = FastAPI(title="agent-tool", version="0.2.0")


def validate_registry_consistency() -> None:
    """启动期校验注册表与执行器一一对应，避免运行期 KeyError。"""
    registered = {tool["name"] for tool in list_tools()}
    executors = set(TOOL_EXECUTORS)
    missing_executor = registered - executors
    missing_definition = executors - registered
    if missing_executor or missing_definition:
        raise RuntimeError(
            f"工具注册表与执行器不一致: missing_executor={sorted(missing_executor)}, missing_definition={sorted(missing_definition)}"
        )


validate_registry_consistency()


@app.get("/health")
def health() -> dict:
    return {"code": 200, "message": "success", "data": {"status": "UP", "service": "agent-tool"}}


@app.get("/v1/tools")
def tools() -> dict:
    return {"code": 200, "message": "success", "data": list_tools()}


@app.post("/v1/tools/{name}/execute")
def execute_tool(name: str, request: ToolExecuteRequest) -> dict:
    trace_id = request.trace_id or f"tool_{uuid4().hex[:12]}"
    definition = get_tool(name)
    if definition is None:
        result = ToolResult(
            status="error",
            summary=f"未知工具: {name}",
            trace_id=trace_id,
            error=ToolError(
                code="tool_not_found",
                message=f"工具 {name} 未注册",
                retryable=False,
                suggested_action="GET /v1/tools 查看可用工具",
            ),
        )
        return {"code": 404, "message": result.summary, "data": result.model_dump()}

    if definition["permission"] == "high_risk" and not request.confirm:
        result = ToolResult(
            status="rejected",
            summary="高风险工具需要显式确认",
            trace_id=trace_id,
            error=ToolError(
                code="confirmation_required",
                message=f"{name} 标记为 high_risk,必须传 confirm=true",
                retryable=True,
                suggested_action="由上游权限服务或人工确认后携带 confirm=true 重试",
            ),
        )
        return {"code": 403, "message": result.summary, "data": result.model_dump()}

    executor = TOOL_EXECUTORS.get(name)
    if executor is None:
        result = ToolResult(
            status="error",
            summary=f"工具 {name} 缺少执行器",
            trace_id=trace_id,
            error=ToolError(
                code="executor_not_found",
                message=f"工具 {name} 已注册但未绑定执行器",
                retryable=False,
                suggested_action="检查 app/tools/__init__.py 中 TOOL_EXECUTORS 配置",
            ),
        )
        return {"code": 500, "message": result.summary, "data": result.model_dump()}

    operation = ""
    if isinstance(request.arguments, dict):
        operation = str(request.arguments.get("operation") or "")
    started_at = time.monotonic()
    logger.info(f"执行工具: tool_name={name}, operation={operation}, trace_id={trace_id}")
    try:
        result = executor(request.arguments, trace_id=trace_id)
    except Exception as exc:
        elapsed_ms = int((time.monotonic() - started_at) * 1000)
        logger.error(f"工具执行异常: tool_name={name}, operation={operation}, trace_id={trace_id}, elapsed_ms={elapsed_ms}, error={exc}")
        result = ToolResult(
            status="error",
            summary=f"工具 {name} 执行异常",
            trace_id=trace_id,
            error=ToolError(
                code="tool_execution_error",
                message=str(exc),
                retryable=True,
                suggested_action="查看 agent-tool 日志定位异常原因后重试",
            ),
        )
    code = _response_code(result)
    elapsed_ms = int((time.monotonic() - started_at) * 1000)
    logger.info(f"工具执行完成: tool_name={name}, operation={operation}, trace_id={trace_id}, status={result.status}, code={code}, elapsed_ms={elapsed_ms}")
    return {"code": code, "message": result.summary, "data": result.model_dump()}


def _response_code(result: ToolResult) -> int:
    if result.status == "success":
        return 200
    if result.status == "rejected":
        return 403
    error_code = result.error.code if result.error else ""
    if error_code == "invalid_arguments":
        return 400
    if error_code == "tool_not_found":
        return 404
    if error_code == "confirmation_required":
        return 403
    return 500
