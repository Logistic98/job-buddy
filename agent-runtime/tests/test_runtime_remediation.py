import pytest

from app.core.agent.executor import AgentExecutor
from app.core.agent.graph import AgentGraphBuilder
from app.core.agent.loop_controller import LoopController
from app.core.common.constants import RuntimeStatus, StepStatus, StopReason
from app.core.context.compactor import ContextCompactor
from app.core.observability.trace import TraceRecorder
from app.core.tool.gateway import ToolGatewayResult
from app.models.schemas import (
    AgentPlan,
    AgentPlanStep,
    AgentRunRequest,
    ChatMessage,
    PermissionRecord,
    TaskUnderstandingResult,
    ToolCall,
    ToolResult,
)


class _Checkpoint:
    async def save(self, *args, **kwargs):
        return None


class _Gateway:
    def __init__(self, results):
        self.results = list(results)

    async def execute(self, *args, **kwargs):
        return self.results.pop(0)


class _TaskUnderstanding:
    def __init__(self, task):
        self.task = task

    async def understand(self, *args, **kwargs):
        return self.task

    def get_profile(self, profile_id):
        return type("Profile", (), {"directive_type": None})()

    def build_directive(self, profile, task):
        return None


class _StreamingLLM:
    def __init__(self):
        self.stream_calls = 0
        self.max_tokens = None

    async def stream_chat(self, messages, max_tokens=None):
        self.stream_calls += 1
        self.max_tokens = max_tokens
        yield {"type": "text", "text": "已合成"}

    def get_cache_metrics(self):
        return {}


def _builder(tmp_path):
    builder = AgentGraphBuilder.__new__(AgentGraphBuilder)
    builder.trace_recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))
    builder.checkpoint_store = _Checkpoint()
    builder.loop_controller = LoopController()
    builder.context_compactor = ContextCompactor()
    return builder


def _task(required_tools=None):
    task = TaskUnderstandingResult(original_query="执行任务")
    task.rewritten_query.planner_query = "执行任务"
    task.metadata["capability_contract"] = {"required_tools": required_tools or []}
    return task


@pytest.mark.asyncio
async def test_observe_consumes_all_parallel_results_once(tmp_path):
    builder = _builder(tmp_path)
    state = {
        "run_id": "run_observe",
        "trace_id": "trace_observe",
        "session_id": "session_observe",
        "tool_results": [
            ToolResult(tool_call_id="c1", tool_name="one", success=True, output="first"),
            ToolResult(tool_call_id="c2", tool_name="two", success=True, output="second"),
        ],
        "observations": [],
        "failure_count": 0,
        "budget": {"max_failures": 3},
    }

    await builder._observe(state)
    await builder._observe(state)

    assert len(state["observations"]) == 2
    assert any("one" in item and "first" in item for item in state["observations"])
    assert any("two" in item and "second" in item for item in state["observations"])
    assert set(state["observed_tool_call_ids"]) == {"c1", "c2"}


@pytest.mark.asyncio
async def test_reflect_updates_plan_steps_summaries_and_decision(tmp_path):
    builder = _builder(tmp_path)
    plan = AgentPlan(
        objective="parallel",
        steps=[
            AgentPlanStep(id="s1", goal="one", tool_name="echo"),
            AgentPlanStep(id="s2", goal="two", tool_name="echo"),
        ],
    )
    calls = [
        ToolCall(id="c1", name="echo", plan_step_id="s1"),
        ToolCall(id="c2", name="echo", plan_step_id="s2"),
    ]
    state = {
        "run_id": "run_reflect",
        "trace_id": "trace_reflect",
        "session_id": "session_reflect",
        "plan": plan,
        "selected_tool_calls": calls,
        "tool_results": [
            ToolResult(tool_call_id="c1", tool_name="echo", success=True, output="one", summary="done one"),
            ToolResult(tool_call_id="c2", tool_name="echo", success=True, output="two", summary="done two"),
        ],
        "failure_count": 0,
        "turn_count": 1,
        "should_stop": False,
    }

    await builder._reflect(state)

    assert [step.status for step in plan.steps] == [StepStatus.SUCCESS, StepStatus.SUCCESS]
    assert [step.result_summary for step in plan.steps] == ["done one", "done two"]
    assert state["reflection"]["decision"] == "replan"
    assert len(state["reflection"]["step_updates"]) == 2


def test_depends_on_gates_dependent_steps_and_rejects_invalid_graphs(tmp_path, fresh_registry):
    builder = _builder(tmp_path)
    builder.tool_runtime = type("Runtime", (), {"registry": fresh_registry})()
    plan = AgentPlan(
        objective="ordered",
        steps=[
            AgentPlanStep(id="root", goal="root", tool_name="echo"),
            AgentPlanStep(id="child", goal="child", tool_name="echo", depends_on=["root"]),
        ],
    )
    calls = [
        ToolCall(id="c1", name="echo", plan_step_id="root"),
        ToolCall(id="c2", name="echo", plan_step_id="child"),
    ]

    assert builder._validate_plan_dependencies(plan, calls) is None
    assert [call.id for call in builder._select_ready_tool_calls(plan, calls)] == ["c1"]
    assert builder._can_execute_in_parallel(calls, plan) is False

    plan.steps[0].status = StepStatus.SUCCESS
    assert [call.id for call in builder._select_ready_tool_calls(plan, calls)] == ["c2"]

    missing = AgentPlan(
        objective="bad",
        steps=[AgentPlanStep(id="x", goal="x", depends_on=["missing"])],
    )
    assert "不存在" in builder._validate_plan_dependencies(missing, [])

    cyclic = AgentPlan(
        objective="cycle",
        steps=[
            AgentPlanStep(id="a", goal="a", depends_on=["b"]),
            AgentPlanStep(id="b", goal="b", depends_on=["a"]),
        ],
    )
    assert "循环依赖" in builder._validate_plan_dependencies(cyclic, [])


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("steps", "expected"),
    [
        ([AgentPlanStep(id="x", goal="x", depends_on=["missing"])], "不存在的依赖"),
        (
            [
                AgentPlanStep(id="a", goal="a", depends_on=["b"]),
                AgentPlanStep(id="b", goal="b", depends_on=["a"]),
            ],
            "循环依赖",
        ),
    ],
)
async def test_plan_node_fails_clearly_for_invalid_dependencies(tmp_path, steps, expected):
    builder = _builder(tmp_path)
    plan = AgentPlan(objective="invalid", steps=steps)

    class _Planner:
        async def create_or_update_plan(self, **kwargs):
            return plan, None

    builder.planner = _Planner()
    state = {
        "run_id": "run_invalid_plan",
        "trace_id": "trace_invalid_plan",
        "session_id": "session_invalid_plan",
        "objective": "invalid",
        "messages": [],
        "observations": [],
        "candidate_tools": [],
        "turn_count": 0,
    }

    await builder._plan(state)

    assert state["status"] == RuntimeStatus.FAIL.value
    assert state["stop_reason"] == StopReason.INVALID_PLAN_DEPENDENCY.value
    assert expected in state["answer"]
    assert state["should_stop"] is True


@pytest.mark.asyncio
async def test_permission_confirmation_remains_need_confirm_terminal(tmp_path):
    builder = _builder(tmp_path)
    call = ToolCall(id="c1", name="danger", plan_step_id="s1")
    result = ToolResult(
        tool_call_id="c1",
        tool_name="danger",
        success=False,
        error="高风险工具需要确认",
        metadata={"permission_denied": True, "requires_confirmation": True},
    )
    record = PermissionRecord(
        tool_call_id="c1",
        tool_name="danger",
        allowed=False,
        reason="高风险工具需要确认",
        requires_confirmation=True,
    )
    builder.tool_gateway = _Gateway([ToolGatewayResult(result=result, permission_record=record)])
    builder.tool_runtime = type("Runtime", (), {"registry": None})()
    state = {
        "run_id": "run_confirm",
        "trace_id": "trace_confirm",
        "session_id": "session_confirm",
        "permission_mode": "default",
        "metadata": {},
        "plan": AgentPlan(objective="danger", steps=[AgentPlanStep(id="s1", goal="danger")]),
        "selected_tool_calls": [call],
        "tool_results": [],
        "permission_records": [],
        "tool_call_count": 0,
        "failure_count": 0,
        "task_understanding": _task(),
    }

    await builder._execute_tool(state)
    await builder._reflect(state)
    await builder._finalize(state)

    assert state["status"] == RuntimeStatus.NEED_CONFIRM.value
    assert state["stop_reason"] == StopReason.PERMISSION_DENIED.value
    assert state["permission_records"][0].requires_confirmation is True
    assert state["task_understanding"].slots.need_confirm == ["danger"]
    assert state["plan"].steps[0].status == StepStatus.BLOCKED
    assert state["reflection"]["decision"] == "need_confirm"


@pytest.mark.asyncio
async def test_regular_tool_failure_continues_until_failure_budget(tmp_path):
    builder = _builder(tmp_path)
    call = ToolCall(id="c1", name="unstable", plan_step_id="s1")
    result = ToolResult(
        tool_call_id="c1",
        tool_name="unstable",
        success=False,
        error="temporary error",
    )
    builder.tool_gateway = _Gateway([ToolGatewayResult(result=result)])
    builder.tool_runtime = type("Runtime", (), {"registry": None})()
    state = {
        "run_id": "run_retry",
        "trace_id": "trace_retry",
        "session_id": "session_retry",
        "permission_mode": "default",
        "metadata": {},
        "budget": {"max_failures": 3, "max_turns": 5},
        "plan": AgentPlan(objective="retry", steps=[AgentPlanStep(id="s1", goal="retry")]),
        "selected_tool_calls": [call],
        "tool_results": [],
        "permission_records": [],
        "tool_call_count": 0,
        "failure_count": 0,
        "turn_count": 1,
        "should_stop": False,
    }

    await builder._execute_tool(state)
    await builder._observe(state)
    await builder._reflect(state)

    assert state["failure_count"] == 1
    assert state["should_stop"] is False
    assert state["plan"].steps[0].status == StepStatus.FAIL
    assert state["reflection"]["decision"] == "retry"
    assert builder.loop_controller.route_after_reflect(state) == "tool_search"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("graph_status", "stop_reason", "answer"),
    [
        (RuntimeStatus.PAUSED.value, StopReason.TOOL_BUDGET_EXCEEDED.value, "预算已耗尽"),
        (RuntimeStatus.FAIL.value, StopReason.TOOL_EXECUTION_FAILED.value, "工具失败"),
        (RuntimeStatus.NEED_CONFIRM.value, StopReason.PERMISSION_DENIED.value, "需要确认"),
    ],
)
async def test_stream_required_tools_preserves_non_success_graph_terminal(
    graph_status, stop_reason, answer, monkeypatch
):
    executor = AgentExecutor(use_llm=False)
    task = _task(["echo"])
    executor.task_understanding = _TaskUnderstanding(task)

    async def fake_execute_required_tools(*args, **kwargs):
        return {
            "status": graph_status,
            "stop_reason": stop_reason,
            "answer": answer,
            "task_understanding": task,
            "tool_results": [ToolResult(tool_call_id="c1", tool_name="echo", success=False, error=answer)],
            "permission_records": [
                PermissionRecord(
                    tool_call_id="c1",
                    tool_name="echo",
                    allowed=False,
                    requires_confirmation=graph_status == RuntimeStatus.NEED_CONFIRM.value,
                )
            ],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", fake_execute_required_tools)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="执行")])
    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert done["status"] == graph_status
    assert done["stop_reason"] == stop_reason
    assert done["answer"] == answer
    assert done["tool_results"][0]["success"] is False
    assert done["permission_records"][0]["requires_confirmation"] is (graph_status == RuntimeStatus.NEED_CONFIRM.value)


@pytest.mark.asyncio
async def test_stream_synthesizes_only_true_required_tool_success(monkeypatch):
    llm = _StreamingLLM()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    task = _task(["echo"])
    executor.task_understanding = _TaskUnderstanding(task)

    async def successful_graph(*args, **kwargs):
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "raw",
            "task_understanding": task,
            "observations": ["echo ok"],
            "tool_results": [ToolResult(tool_call_id="c1", tool_name="echo", success=True, output="ok")],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", successful_graph)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="执行")])
    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert llm.stream_calls == 1
    assert done["status"] == RuntimeStatus.SUCCESS.value
    assert done["answer"] == "已合成"

    async def false_success(*args, **kwargs):
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "没有执行必需工具",
            "task_understanding": task,
            "observations": [],
            "tool_results": [],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", false_success)
    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert llm.stream_calls == 1
    assert done["status"] == RuntimeStatus.FAIL.value
    assert done["stop_reason"] == StopReason.TOOL_EXECUTION_FAILED.value
    assert done["answer"] == "没有执行必需工具"
