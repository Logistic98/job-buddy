from typing import Literal, Optional

from pydantic import BaseModel

from app.core.capability.models import ProfileDefinition
from app.core.common.constants import RuntimeStatus, StopReason
from app.core.common.settings import settings
from app.models.state import AgentGraphState


class BudgetDecision(BaseModel):
    """循环预算判定结果。

    blocked 为 True 时表示当前轮已超出预算，必须终止；reason 面向排错，stop_reason 进入 Trace。
    """

    blocked: bool = False
    reason: Optional[str] = None
    stop_reason: Optional[str] = None


class LoopController:
    """Agent Loop 决策中枢。

    把此前散落在 graph 各节点中的“是否继续 / 为何停止 / 是否进入工具执行”判断集中到一处，
    使状态图节点只负责执行动作并写状态，所有条件边统一委托给本控制器。业务分流保持配置化
    entrypoint 与 profile 字面量，而是读取 directive.next_action 与 Profile 配置。
    """

    def _budget_value(self, state: AgentGraphState, key: str, default: int) -> int:
        budget = state.get("budget") or {}
        return int(budget.get(key) or default)

    def max_tool_calls(self, state: AgentGraphState) -> int:
        return self._budget_value(state, "max_tool_calls", settings.max_tool_calls)

    def evaluate_budget(self, state: AgentGraphState) -> BudgetDecision:
        """工具执行前的硬预算检查：轮数、工具调用次数、连续失败次数、token 用量。

        轮数/工具/失败语义与重构前 `_budget_check` 完全一致（turn 用严格大于，工具/失败用
        大于等于），token 预算与工具预算同语义（达到即停）；本方法是预算阈值的唯一来源，
        避免在多个节点重复硬编码。
        """
        turn = int(state.get("turn_count", 0))
        tool_calls = int(state.get("tool_call_count", 0))
        failures = int(state.get("failure_count", 0))
        if turn > self._budget_value(state, "max_turns", settings.max_turns):
            return BudgetDecision(blocked=True, reason="达到最大轮数", stop_reason=StopReason.MAX_TURNS.value)
        if tool_calls >= self.max_tool_calls(state):
            return BudgetDecision(
                blocked=True, reason="达到工具调用预算", stop_reason=StopReason.TOOL_BUDGET_EXCEEDED.value
            )
        if failures >= self._budget_value(state, "max_failures", settings.max_failures):
            return BudgetDecision(blocked=True, reason="连续失败次数过多", stop_reason=StopReason.MAX_FAILURES.value)
        max_tokens = self._budget_value(state, "max_tokens", settings.max_run_tokens)
        if max_tokens > 0:
            total_tokens = int((state.get("token_usage") or {}).get("total_tokens") or 0)
            if total_tokens >= max_tokens:
                return BudgetDecision(
                    blocked=True, reason="达到 Token 预算", stop_reason=StopReason.TOKEN_BUDGET_EXCEEDED.value
                )
        return BudgetDecision(blocked=False)

    def failure_budget_reached(self, state: AgentGraphState) -> bool:
        """观察阶段使用的连续失败阈值判断。"""
        return int(state.get("failure_count", 0)) >= self._budget_value(state, "max_failures", settings.max_failures)

    def turn_budget_reached(self, state: AgentGraphState) -> bool:
        """反思阶段使用的轮数阈值判断（达到上限即停，比硬预算提前一轮）。"""
        return int(state.get("turn_count", 0)) >= self._budget_value(state, "max_turns", settings.max_turns)

    def route_after_task_understanding(
        self, state: AgentGraphState, profile: ProfileDefinition
    ) -> Literal["finalize", "collect_context"]:
        """任务理解后分流：是否进入上下文装配与工具执行，还是只返回 directive。

        规则全部由协议与配置驱动：澄清/安全拦截直接收尾；无 directive 的通用 Profile 进入 Loop；
        带 directive 的 BFF Profile 只有在能力显式要求 Runtime Planner、预算允许、且入口在
        Profile.runtime_entrypoints 白名单内时才进入 Loop，否则只返回 directive 交由 BFF 执行。
        """
        task = state.get("task_understanding")
        if not task:
            return "collect_context"
        if task.clarification.needed or task.risk_flags.safety_blocked:
            return "finalize"
        directive = state.get("directive")
        if not directive:
            return "collect_context"
        if self.max_tool_calls(state) <= 0:
            return "finalize"
        if directive.get("next_action") != "run_runtime_planner":
            return "finalize"
        entrypoint = str((state.get("metadata") or {}).get("entrypoint") or "")
        if not entrypoint:
            return "finalize"
        return "collect_context" if entrypoint in set(profile.runtime_entrypoints) else "finalize"

    def route_after_plan(self, state: AgentGraphState) -> Literal["finalize", "budget_check"]:
        plan = state.get("plan")
        if (
            state.get("should_stop")
            or (plan and (plan.is_complete or plan.need_clarification))
            or not state.get("selected_tool_call")
        ):
            return "finalize"
        return "budget_check"

    def route_after_budget(self, state: AgentGraphState) -> Literal["execute_tool", "finalize"]:
        return "finalize" if state.get("should_stop") else "execute_tool"

    def route_after_reflect(self, state: AgentGraphState) -> Literal["tool_search", "finalize"]:
        if state.get("should_stop"):
            return "finalize"
        reflection = state.get("reflection") or {}
        decision = str(reflection.get("decision") or "")
        if decision in {"finalize", "need_confirm", "stop_failed"}:
            return "finalize"
        if self.turn_budget_reached(state):
            state["stop_reason"] = StopReason.MAX_TURNS.value
            return "finalize"
        if decision in {"retry", "replan", ""}:
            return "tool_search"
        state["status"] = RuntimeStatus.FAIL.value
        state["stop_reason"] = StopReason.RUNTIME_ERROR.value
        state["answer"] = f"反思阶段返回了未知决策：{decision}。"
        state["should_stop"] = True
        return "finalize"
