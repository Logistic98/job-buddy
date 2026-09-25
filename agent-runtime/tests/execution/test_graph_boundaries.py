from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.agent.graph import AgentGraphBuilder
from app.core.common.constants import StepStatus
from app.core.common.settings import settings
from app.core.context.compactor import ContextCompactor
from app.core.intent.task_understanding import TaskUnderstandingService
from app.core.planner.planner import RuntimePlanner
from app.core.tool.runtime import ToolRuntime
from app.core.tool.search import ToolSearchService
from app.models.schemas import AgentPlan, AgentPlanStep, TaskUnderstandingResult, ToolCall, ToolResult


@pytest.fixture
def graph(fresh_registry):
    return AgentGraphBuilder(
        planner=RuntimePlanner(),
        tool_search=ToolSearchService(fresh_registry),
        tool_runtime=ToolRuntime(fresh_registry),
        task_understanding=TaskUnderstandingService(allow_semantic_fallback=True),
        checkpoint_store=SimpleNamespace(save=AsyncMock()),
        trace_recorder=SimpleNamespace(record=AsyncMock()),
    )


def state(**changes):
    return {
        "session_id": "session",
        "run_id": "run",
        "trace_id": "trace",
        "objective": "goal",
        "metadata": {},
        "logs": [],
        **changes,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("changes", "status", "reason"),
    [
        (
            {"task_understanding": TaskUnderstandingResult(original_query="goal", risk_flags={"safety_blocked": True})},
            "paused",
            "safety_blocked",
        ),
        (
            {
                "task_understanding": TaskUnderstandingResult(
                    original_query="goal", clarification={"needed": True, "question": "Which file?"}
                )
            },
            "paused",
            "need_clarification",
        ),
        (
            {"plan": AgentPlan(objective="goal", need_clarification=True, clarification_question="Which file?")},
            "paused",
            "need_clarification",
        ),
        ({"stop_reason": "max_turns"}, "paused", "max_turns"),
        ({"stop_reason": "max_failures"}, "fail", "max_failures"),
    ],
)
async def test_graph_finalize_preserves_safety_clarification_and_budget_outcomes(graph, changes, status, reason):
    result = await graph._finalize(state(**changes))
    assert result["status"] == status
    assert result["stop_reason"] == reason
    if reason == "need_clarification":
        assert result["answer"] == "Which file?"
    graph.checkpoint_store.save.assert_awaited_once()


@pytest.mark.asyncio
async def test_graph_finalize_uses_directive_answer_and_budget_blocks_execution(graph):
    result = await graph._finalize(state(directive={"answer": "verified"}))
    assert result["answer"] == "verified"
    result = await graph._budget_check(state(turn_count=2, budget={"max_turns": 1}))
    assert result["should_stop"] and result["status"] == "paused"
    assert result["stop_reason"] == "max_turns"
    empty = await graph._execute_tool(state())
    assert empty["should_stop"]


@pytest.mark.asyncio
async def test_graph_parallel_readonly_calls_return_both_results(graph, monkeypatch):
    calls = [
        ToolCall(id="one", name="echo", arguments={"text": "first"}),
        ToolCall(id="two", name="echo", arguments={"text": "second"}),
    ]
    result = await graph._execute_tool(state(selected_tool_calls=calls))
    assert [item.output for item in result["tool_results"]] == [{"text": "first"}, {"text": "second"}]
    assert all(item.success for item in result["tool_results"])
    assert not graph._can_execute_in_parallel([calls[0], ToolCall(id="missing", name="absent")], None)


@pytest.mark.asyncio
async def test_graph_permission_denial_stops_without_confirmation(graph, monkeypatch):
    monkeypatch.setattr(settings.config.permission, "deny_tools", ["echo"])
    result = await graph._execute_tool(
        state(selected_tool_calls=[ToolCall(id="one", name="echo", arguments={"text": "first"})])
    )
    assert result["status"] == "paused"
    assert result["stop_reason"] == "permission_denied"
    assert result["should_stop"]


@pytest.mark.asyncio
async def test_graph_observation_compacts_and_stops_at_failure_budget(graph):
    graph.context_compactor = ContextCompactor(enabled=True, trigger_observations=1, keep_recent=1)
    result = await graph._observe(
        state(
            observations=["old", "recent"],
            failure_count=2,
            budget={"max_failures": 2},
            tool_results=[ToolResult(tool_call_id="call", tool_name="echo", success=False, error="offline")],
        )
    )
    assert result["status"] == "fail" and result["stop_reason"] == "max_failures"
    assert result["compaction"]["folded_observations"] >= 1
    assert any(call.args[1] == "context_compaction" for call in graph.trace_recorder.record.await_args_list)


@pytest.mark.asyncio
async def test_graph_resume_skips_completed_observe_reflect_and_disabled_search_lists_tools(graph, monkeypatch):
    original = state(_resume_skip_until="reflect")
    assert await graph._observe(original) is original
    assert await graph._reflect(original) is original
    graph.checkpoint_store.save.assert_not_awaited()
    assert not graph._should_skip_resume_stage(state(_resume_skip_until="unknown"), "plan")
    assert graph._route_after_task_understanding(state(metadata={"understanding_only": True})) == "finalize"
    monkeypatch.setattr(settings.config.tool_search, "enabled", False)
    result = await graph._tool_search(state())
    assert result["candidate_tools"]


@pytest.mark.parametrize(
    ("steps", "calls", "message"),
    [
        ([AgentPlanStep(id="one", goal="first"), AgentPlanStep(id="one", goal="duplicate")], [], "ID 重复"),
        ([AgentPlanStep(id="one", goal="self", depends_on=["one"])], [], "自身"),
        (
            [AgentPlanStep(id="one", goal="first")],
            [ToolCall(id="call", name="echo", plan_step_id="missing")],
            "不存在的步骤",
        ),
    ],
)
def test_graph_rejects_invalid_plan_dependencies(graph, steps, calls, message):
    assert message in graph._validate_plan_dependencies(AgentPlan(objective="goal", steps=steps), calls)


def test_graph_blocks_failed_dependencies_but_allows_unbound_calls(graph):
    plan = AgentPlan(
        objective="goal",
        steps=[
            AgentPlanStep(id="first", goal="first", status=StepStatus.FAIL),
            AgentPlanStep(id="next", goal="next", depends_on=["first"]),
        ],
    )
    unbound = ToolCall(id="free", name="echo")
    dependent = ToolCall(id="dependent", name="echo", plan_step_id="next")
    assert graph._select_ready_tool_calls(plan, [unbound, dependent]) == [unbound]
    assert plan.steps[1].status == StepStatus.BLOCKED
    assert "依赖步骤" in plan.steps[1].error


@pytest.mark.asyncio
async def test_graph_reflection_replans_when_result_is_missing_and_respects_stop(graph):
    pending = state(
        selected_tool_calls=[ToolCall(id="call", name="echo", plan_step_id="first")],
        plan=AgentPlan(objective="goal", steps=[AgentPlanStep(id="first", goal="first")]),
    )
    result = await graph._reflect(pending)
    assert result["reflection"]["decision"] == "replan"
    pending["should_stop"] = True
    assert (await graph._reflect(pending))["reflection"]["decision"] == "finalize"


@pytest.mark.asyncio
async def test_graph_fails_closed_when_all_calls_depend_on_failed_step(graph):
    call = ToolCall(id="call", name="echo", plan_step_id="second")
    plan = AgentPlan(
        objective="goal",
        steps=[
            AgentPlanStep(id="first", goal="first", status=StepStatus.FAIL),
            AgentPlanStep(id="second", goal="second", depends_on=["first"]),
        ],
        tool_calls=[call],
    )
    graph.planner.create_or_update_plan = AsyncMock(return_value=(plan, call))
    result = await graph._plan(state())
    assert result["status"] == "fail"
    assert result["stop_reason"] == "invalid_plan_dependency"
    assert result["selected_tool_calls"] == []
    assert "没有可安全执行" in result["answer"]


def test_unrestricted_web_search_observation_retains_results(graph):
    result = ToolResult(
        tool_call_id="call", tool_name="web_search", success=True, output={"results": [{"title": "Reference"}]}
    )
    assert "Reference" in graph._tool_observation(result)
