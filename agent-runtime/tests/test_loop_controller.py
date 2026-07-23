from app.core.agent.loop_controller import LoopController
from app.core.capability.models import CapabilityCard, ProfileDefinition
from app.core.common.constants import StopReason
from app.core.common.settings import settings
from app.models.schemas import (
    ClarificationDecision,
    RiskFlags,
    TaskUnderstandingResult,
)


def _task(clarification_needed=False, safety_blocked=False) -> TaskUnderstandingResult:
    return TaskUnderstandingResult(
        trace_id="t",
        profile="job-buddy",
        clarification=ClarificationDecision(needed=clarification_needed),
        risk_flags=RiskFlags(safety_blocked=safety_blocked),
    )


def _bff_profile() -> ProfileDefinition:
    return ProfileDefinition(
        id="job-buddy",
        name="Job Buddy",
        directive_type="job_buddy_directive",
        runtime_entrypoints=["chat.ask", "agent.run"],
        capabilities=[CapabilityCard(id="c", name="c", intent="c")],
    )


def _generic_profile() -> ProfileDefinition:
    return ProfileDefinition(id="default", name="Default")


def test_budget_blocks_on_turns_tools_failures():
    controller = LoopController()
    assert (
        controller.evaluate_budget({"turn_count": 99, "budget": {"max_turns": 5}}).stop_reason
        == StopReason.MAX_TURNS.value
    )
    assert (
        controller.evaluate_budget({"tool_call_count": 5, "budget": {"max_tool_calls": 5}}).stop_reason
        == StopReason.TOOL_BUDGET_EXCEEDED.value
    )
    assert (
        controller.evaluate_budget({"failure_count": 3, "budget": {"max_failures": 3}}).stop_reason
        == StopReason.MAX_FAILURES.value
    )
    assert (
        controller.evaluate_budget(
            {"turn_count": 1, "budget": {"max_turns": 5, "max_tool_calls": 5, "max_failures": 3}}
        ).blocked
        is False
    )


def test_budget_blocks_on_token_usage():
    controller = LoopController()
    state = {"budget": {"max_tokens": 1000}, "token_usage": {"total_tokens": 1000}}
    decision = controller.evaluate_budget(state)
    assert decision.blocked is True
    assert decision.stop_reason == StopReason.TOKEN_BUDGET_EXCEEDED.value
    # 未达阈值不阻断
    assert (
        controller.evaluate_budget({"budget": {"max_tokens": 1000}, "token_usage": {"total_tokens": 999}}).blocked
        is False
    )
    # max_tokens 为 0 或缺省时回落到服务端有限预算，不能绕过 run 级上限。
    assert (
        controller.evaluate_budget(
            {
                "budget": {"max_tokens": 0},
                "token_usage": {"total_tokens": settings.config.runtime.max_run_tokens},
            }
        ).stop_reason
        == StopReason.TOKEN_BUDGET_EXCEEDED.value
    )
    assert (
        controller.evaluate_budget(
            {
                "budget": {},
                "token_usage": {"total_tokens": settings.config.runtime.max_run_tokens - 1},
            }
        ).blocked
        is False
    )
    # 无 token_usage 状态不阻断
    assert controller.evaluate_budget({"budget": {"max_tokens": 1000}}).blocked is False


def test_turn_budget_reached_is_inclusive_unlike_hard_budget():
    controller = LoopController()
    state = {"turn_count": 5, "budget": {"max_turns": 5}}
    # 反思阶段达到上限即停（>=），而硬预算用严格大于（>），二者语义保持区分。
    assert controller.turn_budget_reached(state) is True
    assert controller.evaluate_budget(state).blocked is False


def test_route_generic_profile_without_directive_enters_loop():
    controller = LoopController()
    state = {"task_understanding": _task(), "directive": None, "budget": {"max_tool_calls": 5}, "metadata": {}}
    assert controller.route_after_task_understanding(state, _generic_profile()) == "collect_context"


def test_route_clarification_or_safety_finalizes():
    controller = LoopController()
    profile = _bff_profile()
    s1 = {"task_understanding": _task(clarification_needed=True), "directive": {"next_action": "run_runtime_planner"}}
    s2 = {"task_understanding": _task(safety_blocked=True), "directive": {"next_action": "run_runtime_planner"}}
    assert controller.route_after_task_understanding(s1, profile) == "finalize"
    assert controller.route_after_task_understanding(s2, profile) == "finalize"


def test_route_bff_directive_only_unless_runtime_entrypoint_and_planner():
    controller = LoopController()
    profile = _bff_profile()
    base = {"task_understanding": _task(), "budget": {"max_tool_calls": 5}}

    # directive 未要求 runtime planner -> 只返回 directive
    assert (
        controller.route_after_task_understanding(
            {**base, "directive": {"next_action": "call_get_recommend_jobs"}, "metadata": {"entrypoint": "chat.ask"}},
            profile,
        )
        == "finalize"
    )
    # 要求 planner 但无 entrypoint -> 只返回 directive
    assert (
        controller.route_after_task_understanding(
            {**base, "directive": {"next_action": "run_runtime_planner"}, "metadata": {}}, profile
        )
        == "finalize"
    )
    # 要求 planner 且 entrypoint 不在白名单（如 chat.stream/intent.classify）-> 只返回 directive
    assert (
        controller.route_after_task_understanding(
            {**base, "directive": {"next_action": "run_runtime_planner"}, "metadata": {"entrypoint": "chat.stream"}},
            profile,
        )
        == "finalize"
    )
    # 要求 planner 且 entrypoint 在白名单 -> 进入 Loop
    assert (
        controller.route_after_task_understanding(
            {**base, "directive": {"next_action": "run_runtime_planner"}, "metadata": {"entrypoint": "agent.run"}},
            profile,
        )
        == "collect_context"
    )


def test_route_after_plan_and_budget_and_reflect():
    controller = LoopController()
    assert controller.route_after_plan({"should_stop": True}) == "finalize"
    assert controller.route_after_plan({"selected_tool_call": None}) == "finalize"
    assert controller.route_after_plan({"selected_tool_call": object()}) == "budget_check"
    assert controller.route_after_budget({"should_stop": True}) == "finalize"
    assert controller.route_after_budget({}) == "execute_tool"
    assert controller.route_after_reflect({"should_stop": True}) == "finalize"
    assert controller.route_after_reflect({"reflection": {"decision": "need_confirm"}}) == "finalize"
    assert (
        controller.route_after_reflect(
            {"reflection": {"decision": "retry"}, "turn_count": 1, "budget": {"max_turns": 5}}
        )
        == "tool_search"
    )
    assert (
        controller.route_after_reflect(
            {"reflection": {"decision": "replan"}, "turn_count": 1, "budget": {"max_turns": 5}}
        )
        == "tool_search"
    )

    invalid = {"reflection": {"decision": "unknown"}, "turn_count": 1, "budget": {"max_turns": 5}}
    assert controller.route_after_reflect(invalid) == "finalize"
    assert invalid["status"] == "fail"
    assert invalid["stop_reason"] == "runtime_error"
