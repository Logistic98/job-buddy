import asyncio
from typing import Optional

from loguru import logger

from app.core.common.constants import PermissionMode
from app.core.common.settings import settings
from app.core.tool.base import ToolExecutionContext
from app.core.tool.permission import PermissionDecision, PermissionService
from app.core.tool.registry import ToolRegistry
from app.models.schemas import PermissionRecord, ToolCall, ToolResult


class ToolRuntime:
    """工具运行时：统一处理查找、权限、安全执行、重试、结果标准化。"""

    def __init__(self, registry: ToolRegistry, permission_service: PermissionService = None):
        self.registry = registry
        self.permission_service = permission_service or PermissionService()
        self.last_permission_record: Optional[PermissionRecord] = None

    async def execute(
        self,
        call: ToolCall,
        permission_mode: PermissionMode,
        context: ToolExecutionContext,
        permission_decision: PermissionDecision | None = None,
    ) -> ToolResult:
        self.last_permission_record = None
        if not settings.config.tool_runtime.enabled:
            return ToolResult(tool_call_id=call.id, tool_name=call.name, success=False, error="工具运行时已关闭")

        tool = self.registry.get(call.name)
        if not tool:
            return ToolResult(
                tool_call_id=call.id, tool_name=call.name, success=False, error=f"工具不存在: {call.name}"
            )

        decision = permission_decision or await self.permission_service.check(tool.definition(), call, permission_mode)
        self.last_permission_record = PermissionRecord(
            tool_call_id=call.id,
            tool_name=tool.name,
            allowed=decision.allowed,
            reason=decision.reason,
            requires_confirmation=decision.requires_confirmation,
            mode=permission_mode.value,
        )
        if not decision.allowed:
            logger.warning(f"工具权限拒绝：tool={call.name}, reason={decision.reason}")
            return ToolResult(
                tool_call_id=call.id,
                tool_name=tool.name,
                success=False,
                error=decision.reason,
                metadata={"requires_confirmation": decision.requires_confirmation, "permission_denied": True},
            )

        max_retries = tool.max_retries if tool.max_retries is not None else settings.config.tool_runtime.max_retries
        backoff = settings.config.tool_runtime.retry_backoff_seconds
        last_result: ToolResult = ToolResult(tool_call_id=call.id, tool_name=tool.name, success=False, error="未执行")
        for attempt in range(max_retries + 1):
            if attempt > 0:
                await asyncio.sleep(backoff * (2 ** (attempt - 1)))
            result = await tool.safe_run(
                ToolCall(id=call.id, name=tool.name, arguments=call.arguments, reason=call.reason), context
            )
            result.metadata["attempt"] = attempt + 1
            last_result = result
            if result.success:
                return result
            logger.warning(f"工具执行重试：tool={tool.name}, attempt={attempt + 1}, error={result.error}")
        return last_result
