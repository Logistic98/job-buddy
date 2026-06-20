"""sandbox_execute：通过 agent-sandbox 执行不可信命令。

高风险工具:调用方必须显式传 confirm=true,由 server 层拦截。
目标地址通过环境变量 AGENT_TOOL_SANDBOX_BASE_URL 注入。
"""

import os
from typing import Any, Dict

import httpx
from loguru import logger

from ..models import ToolError, ToolResult

_DEFAULT_BASE_URL = "http://localhost:8000"
_DEFAULT_TIMEOUT = 60.0


def run_sandbox_execute(arguments: Dict[str, Any], trace_id: str = None) -> ToolResult:
    command = str(arguments.get("command", "")).strip()
    if not command:
        return ToolResult(
            status="error",
            summary="缺少 command 参数",
            trace_id=trace_id,
            error=ToolError(
                code="invalid_arguments",
                message="arguments.command 不能为空",
                retryable=False,
                suggested_action="提供需要在沙箱内执行的 shell 命令",
            ),
        )

    base_url = os.getenv("AGENT_TOOL_SANDBOX_BASE_URL", _DEFAULT_BASE_URL).rstrip("/")
    timeout = float(os.getenv("AGENT_TOOL_SANDBOX_TIMEOUT_SECONDS", str(_DEFAULT_TIMEOUT)))
    payload: Dict[str, Any] = {"command": command}
    if arguments.get("timeout"):
        payload["options"] = {"timeout": float(arguments["timeout"]), "check": False}
    else:
        payload["options"] = {"check": False}

    try:
        response = httpx.post(f"{base_url}/v1/shell", json=payload, timeout=timeout)
        response.raise_for_status()
        body = response.json()
        ok = bool(body.get("ok")) and int(body.get("returncode", 1)) == 0
        data = {
            "returncode": body.get("returncode"),
            "stdout": body.get("stdout", ""),
            "stderr": body.get("stderr", ""),
        }
        if ok:
            return ToolResult(status="success", summary="沙箱命令执行成功", data=data, trace_id=trace_id)
        return ToolResult(
            status="error",
            summary=f"沙箱命令退出码 {body.get('returncode')}",
            data=data,
            trace_id=trace_id,
            error=ToolError(
                code="command_failed",
                message=(body.get("stderr") or "")[:500],
                retryable=False,
                suggested_action="检查命令本身或沙箱策略限制",
            ),
        )
    except httpx.TimeoutException:
        logger.warning(f"sandbox_execute 超时: base_url={base_url}, trace_id={trace_id}")
        return ToolResult(
            status="error",
            summary="agent-sandbox 执行超时",
            trace_id=trace_id,
            error=ToolError(
                code="sandbox_timeout",
                message=f"请求 {base_url} 超过 {timeout}s",
                retryable=True,
                suggested_action="缩短命令运行时间或调大 AGENT_TOOL_SANDBOX_TIMEOUT_SECONDS",
            ),
        )
    except Exception as e:
        logger.warning(f"sandbox_execute 失败: {e}, trace_id={trace_id}")
        return ToolResult(
            status="error",
            summary="agent-sandbox 调用失败",
            trace_id=trace_id,
            error=ToolError(
                code="sandbox_unavailable",
                message=str(e),
                retryable=True,
                suggested_action="确认 AGENT_TOOL_SANDBOX_BASE_URL 指向可用的 agent-sandbox 服务",
            ),
        )
