"""Runtime 侧 Boss 工具代理。

具体 Boss 实现属于 agent-tool；Runtime 只保留工具元数据、权限参与和编排入口，
执行时转发到 agent-tool 的 /v1/tools/boss_browser/execute。
"""

from __future__ import annotations

import os
from typing import Any, Dict

import httpx

from app.core.common.constants import ToolRiskLevel
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class BossBrowserTool(BaseTool):
    name = "boss_browser"
    aliases = ["boss_zhipin", "boss_search_jobs", "boss_job_detail", "boss_login_status"]
    search_hint = "Boss 直聘 招聘 求职 岗位 JD 在线简历 boss-cli Cookie 登录"
    description = (
        "Boss 直聘 boss-cli 工具代理。具体实现位于 agent-tool；本工具仅负责代理调用。"
        "凭据通常由 Backend 的 PostgreSQL auth_state 注入，二维码登录为默认兜底；"
        "refresh_auth 仅在显式启用浏览器 Cookie 导入时使用，不启动或连接 Chrome CDP。"
    )
    input_schema = {
        "type": "object",
        "properties": {
            "operation": {
                "type": "string",
                "description": "操作类型: status, refresh_auth, qr_start, qr_status, qr_cancel, search, favorite_list, detail, profile, rate",
                "enum": [
                    "status",
                    "refresh_auth",
                    "qr_start",
                    "qr_status",
                    "qr_cancel",
                    "search",
                    "favorite_list",
                    "detail",
                    "profile",
                    "rate",
                ],
            },
            "payload": {
                "type": "object",
                "description": "操作参数。search 支持筛选和页码；favorite_list 只支持 page；detail 支持 securityId/url。",
            },
        },
        "required": ["operation"],
    }
    output_schema = {
        "type": "object",
        "properties": {
            "code": {"type": "integer"},
            "message": {"type": "string"},
            "data": {"type": "object"},
        },
    }
    tags = ["boss", "job", "browser", "agent-tool"]
    timeout_seconds = 190
    # boss_browser 同时被 Java 后端作为业务 API 代理调用，二维码登录会返回 10KB+ base64 图片。
    # 不能使用 BaseTool 默认 12KB 截断，否则 Java 侧拿到 preview/result_path 后会丢失 {code,message,data} 信封。
    max_result_size_chars = 250000
    max_retries = 0
    risk_level = ToolRiskLevel.MEDIUM
    read_only = False
    concurrency_safe = False
    should_defer = True
    always_load = False

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        validation = await super().validate_input(arguments, context)
        if not validation.result:
            return validation
        operation = str(arguments.get("operation") or "").strip()
        allowed = {
            "status",
            "refresh_auth",
            "qr_start",
            "qr_status",
            "qr_cancel",
            "search",
            "favorite_list",
            "detail",
            "profile",
            "rate",
        }
        if operation not in allowed:
            return ValidationResult(result=False, message=f"不支持的 Boss 操作: {operation}", error_code=400)
        payload = arguments.get("payload", {})
        if payload is None:
            payload = {}
        if not isinstance(payload, dict):
            return ValidationResult(result=False, message="payload 必须是对象", error_code=400)
        tenant_id = str(context.metadata.get("tenant_id") or "").strip()
        operator_id = str(context.metadata.get("operator_id") or context.metadata.get("user_id") or "").strip()
        if not tenant_id or not operator_id:
            return ValidationResult(
                result=False,
                message="Boss 工具必须由已认证的租户与操作人调用",
                error_code=403,
            )
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        base_url = os.getenv("AGENT_TOOL_URL", "http://127.0.0.1:8040").rstrip("/")
        url = f"{base_url}/v1/tools/boss_browser/execute"
        request = {
            "arguments": arguments,
            "confirm": True,
            "trace_id": context.trace_id,
        }
        headers = {
            "X-Tenant-Id": str(context.metadata["tenant_id"]).strip(),
            "X-Operator-Id": str(context.metadata.get("operator_id") or context.metadata["user_id"]).strip(),
        }
        token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
        if token:
            headers["X-Internal-Service-Token"] = token
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            response = await client.post(url, json=request, headers=headers)
            response.raise_for_status()
            body = response.json()

        if not isinstance(body, dict):
            raise RuntimeError("agent-tool 返回不是 JSON 对象")
        tool_result = body.get("data")
        if not isinstance(tool_result, dict):
            raise RuntimeError("agent-tool 响应缺少 data")

        # boss_browser 执行器在 ToolResult.data 中返回 {code,message,data} 业务信封。
        envelope = tool_result.get("data")
        if isinstance(envelope, dict) and "code" in envelope and "message" in envelope:
            return envelope

        error = tool_result.get("error") or {}
        message = error.get("message") if isinstance(error, dict) else None
        raise RuntimeError(message or tool_result.get("summary") or "agent-tool boss_browser 执行失败")
