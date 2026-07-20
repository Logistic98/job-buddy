from pydantic import BaseModel

from app.core.common.constants import PermissionMode, ToolRiskLevel
from app.core.common.settings import settings
from app.models.schemas import ToolCall, ToolDefinition


class PermissionDecision(BaseModel):
    allowed: bool
    reason: str = ""
    requires_confirmation: bool = False


class PermissionService:
    """工具权限服务。

    权限边界不依赖 Prompt，而是在 Runtime 中依据配置、工具元数据和当前模式统一判断。
    """

    async def check(self, tool: ToolDefinition, call: ToolCall, mode: PermissionMode) -> PermissionDecision:
        config = settings.config.permission

        if call.name in config.deny_tools or tool.name in config.deny_tools:
            return PermissionDecision(allowed=False, reason="工具命中 deny_tools", requires_confirmation=False)

        if config.allow_tools and tool.name not in config.allow_tools and call.name not in config.allow_tools:
            return PermissionDecision(allowed=False, reason="工具不在 allow_tools 中", requires_confirmation=False)

        if mode == PermissionMode.BYPASS:
            return PermissionDecision(allowed=True, reason="bypass mode")

        if mode == PermissionMode.PLAN:
            return PermissionDecision(allowed=False, reason="plan mode 禁止执行工具", requires_confirmation=False)

        if tool.destructive or tool.name in config.destructive_tools:
            if mode != PermissionMode.AUTO:
                return PermissionDecision(
                    allowed=False, reason="破坏性工具需要确认或 auto 模式", requires_confirmation=True
                )

        if tool.risk_level == ToolRiskLevel.HIGH:
            if mode == PermissionMode.AUTO or config.allow_high_risk_in_default:
                return PermissionDecision(allowed=True, reason="高风险工具已按策略允许")
            return PermissionDecision(allowed=False, reason="高风险工具需要确认", requires_confirmation=True)

        return PermissionDecision(allowed=True, reason="allowed")
