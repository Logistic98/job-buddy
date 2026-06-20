"""memory_search：检索 agent-memory 服务中的记忆片段。

目标地址通过环境变量 AGENT_TOOL_MEMORY_BASE_URL 注入,
默认指向本地 agent-memory 服务。
"""

import os
from typing import Any, Dict

import httpx
from loguru import logger

from ..models import ToolError, ToolResult

_DEFAULT_BASE_URL = "http://localhost:8030"
_DEFAULT_TIMEOUT = 5.0


def run_memory_search(arguments: Dict[str, Any], trace_id: str = None) -> ToolResult:
    query = str(arguments.get("query", "")).strip()
    if not query:
        return ToolResult(
            status="error",
            summary="缺少 query 参数",
            trace_id=trace_id,
            error=ToolError(
                code="invalid_arguments",
                message="arguments.query 不能为空",
                retryable=False,
                suggested_action="提供检索关键词",
            ),
        )

    base_url = os.getenv("AGENT_TOOL_MEMORY_BASE_URL", _DEFAULT_BASE_URL).rstrip("/")
    timeout = float(os.getenv("AGENT_TOOL_MEMORY_TIMEOUT_SECONDS", str(_DEFAULT_TIMEOUT)))
    params = {"q": query}
    scope = arguments.get("scope")
    if scope:
        params["scope"] = str(scope)

    max_attempts = 2
    try:
        for attempt in range(1, max_attempts + 1):
            try:
                response = httpx.get(f"{base_url}/v1/memories/search", params=params, timeout=timeout)
                response.raise_for_status()
                break
            except (httpx.TimeoutException, httpx.TransportError):
                if attempt >= max_attempts:
                    raise
                logger.warning(f"memory_search 瞬时失败，重试 {attempt}/{max_attempts - 1}: base_url={base_url}, trace_id={trace_id}")
        body = response.json()
        items = body.get("data", [])
        return ToolResult(
            status="success",
            summary=f"命中 {len(items)} 条记忆",
            data=items,
            trace_id=trace_id,
        )
    except httpx.TimeoutException:
        logger.warning(f"memory_search 超时: base_url={base_url}, trace_id={trace_id}")
        return ToolResult(
            status="error",
            summary="agent-memory 检索超时",
            trace_id=trace_id,
            error=ToolError(
                code="memory_timeout",
                message=f"请求 {base_url} 超过 {timeout}s",
                retryable=True,
                suggested_action="稍后重试或检查 agent-memory 服务状态",
            ),
        )
    except Exception as e:
        logger.warning(f"memory_search 失败: {e}, trace_id={trace_id}")
        return ToolResult(
            status="error",
            summary="agent-memory 检索失败",
            trace_id=trace_id,
            error=ToolError(
                code="memory_unavailable",
                message=str(e),
                retryable=True,
                suggested_action="确认 AGENT_TOOL_MEMORY_BASE_URL 指向可用的 agent-memory 服务",
            ),
        )
