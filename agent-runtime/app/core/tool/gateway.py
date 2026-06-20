
from __future__ import annotations

from typing import Any, Dict, List, Optional

from loguru import logger
from pydantic import BaseModel, Field

from app.core.common.constants import PermissionMode
from app.core.tool.base import ToolExecutionContext
from app.core.tool.permission import PermissionService
from app.core.tool.registry import ToolRegistry
from app.core.tool.runtime import ToolRuntime
from app.core.tool.search import ToolSearchService
from app.models.schemas import PermissionRecord, TaskUnderstandingResult, ToolCall, ToolDefinition, ToolResult


class ToolGatewayResult(BaseModel):
    result: ToolResult
    permission_record: Optional[PermissionRecord] = None


class ToolGateway:
    """工具治理网关。

    Agent Loop 只依赖 search/execute 两个入口，网关内部统一完成候选范围收窄、权限、执行、结果规范化和审计记录。
    """

    def __init__(
        self,
        registry: ToolRegistry,
        search_service: ToolSearchService | None = None,
        runtime: ToolRuntime | None = None,
        permission_service: PermissionService | None = None,
    ):
        self.registry = registry
        self.search_service = search_service or ToolSearchService(registry)
        self.runtime = runtime or ToolRuntime(registry, permission_service=permission_service)
        self.permission_service = permission_service or self.runtime.permission_service

    async def search(self, query: str, task: TaskUnderstandingResult | None, limit: int) -> List[ToolDefinition]:
        candidates = await self.search_service.search(query or "", limit=limit)
        candidates = self._filter_by_task_scope(candidates, task)
        candidates = self._include_required_tools(task, candidates, limit)
        return candidates[: max(limit, len(candidates))]

    async def execute(
        self,
        call: ToolCall,
        permission_mode: PermissionMode,
        context: ToolExecutionContext,
        task: TaskUnderstandingResult | None = None,
    ) -> ToolGatewayResult:
        if task and not self._call_allowed_by_task(call.name, task):
            result = ToolResult(
                tool_call_id=call.id,
                tool_name=call.name,
                success=False,
                error="工具不在当前能力声明的允许范围内",
                metadata={"permission_denied": True, "policy": "capability_tool_scope"},
            )
            record = PermissionRecord(
                tool_call_id=call.id,
                tool_name=call.name,
                allowed=False,
                reason=result.error or "capability_tool_scope",
                requires_confirmation=False,
                mode=permission_mode.value,
            )
            return ToolGatewayResult(result=result, permission_record=record)

        # 每次调用使用独立 ToolRuntime，隔离并发 permission record。
        runtime = ToolRuntime(self.registry, permission_service=self.permission_service)
        result = await runtime.execute(call, permission_mode, context)
        result = self._normalize_result(result)
        return ToolGatewayResult(result=result, permission_record=runtime.last_permission_record)

    def _filter_by_task_scope(self, tools: List[ToolDefinition], task: TaskUnderstandingResult | None) -> List[ToolDefinition]:
        allowed = set(self._task_tool_scope(task))
        if not allowed:
            return tools
        # 能力卡声明了工具范围时严格收窄候选集：即使关键词召回没命中范围内工具，
        # 也不能回退到全量工具，否则候选集会包含 echo 等范围外工具，Planner 选中后会在
        # 执行阶段被 _call_allowed_by_task 拒绝（permission_denied），造成纯生成任务直接失败。
        # 范围内工具由 _include_required_tools 负责补齐。
        return [tool for tool in tools if tool.name in allowed or tool.always_load]

    def _include_required_tools(self, task: TaskUnderstandingResult | None, tools: List[ToolDefinition], limit: int) -> List[ToolDefinition]:
        required_names = self._task_tool_scope(task, required_only=True)
        selected = list(tools or [])
        seen = {tool.name for tool in selected}
        for name in required_names:
            if name in seen:
                continue
            tool = self.registry.get(str(name))
            if not tool:
                logger.warning(f"能力声明工具不存在：tool={name}")
                continue
            selected.append(tool.definition())
            seen.add(tool.name)
        return selected[: max(limit, len(selected))]

    def _task_tool_scope(self, task: TaskUnderstandingResult | None, required_only: bool = False) -> List[str]:
        if not task or not isinstance(task.metadata, dict):
            return []
        implementation = task.metadata.get("implementation")
        if not isinstance(implementation, dict):
            return []
        required = [str(item) for item in (implementation.get("required_tools") or [])]
        if required_only:
            return required
        allowed = [str(item) for item in (implementation.get("allowed_tools") or implementation.get("tools") or [])]
        return list(dict.fromkeys(required + allowed))

    def _call_allowed_by_task(self, tool_name: str, task: TaskUnderstandingResult) -> bool:
        scope = self._task_tool_scope(task)
        tool = self.registry.get(tool_name)
        if tool and tool.definition().always_load:
            return True
        return not scope or tool_name in scope

    def _normalize_result(self, result: ToolResult) -> ToolResult:
        metadata: Dict[str, Any] = dict(result.metadata or {})
        metadata.setdefault("normalized", True)
        metadata.setdefault("status", "success" if result.success else "error")
        metadata.setdefault("retryable", not result.success and not metadata.get("permission_denied"))
        if not result.success and result.error and "suggested_action" not in metadata:
            metadata["suggested_action"] = "检查工具参数、权限或外部服务状态后重试"
        result.metadata = metadata
        return result
