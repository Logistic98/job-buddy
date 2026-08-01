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
    CapabilityCandidate,
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
        self.chat_calls = 0
        self.max_tokens = None
        self.stream_disable_thinking = None
        self.chat_disable_thinking = None

    async def stream_chat(self, messages, max_tokens=None, disable_thinking=False):
        self.stream_calls += 1
        self.max_tokens = max_tokens
        self.stream_disable_thinking = disable_thinking
        yield {"type": "text", "text": "已合成"}

    async def chat(self, messages, max_tokens=None, disable_thinking=False):
        self.chat_calls += 1
        self.max_tokens = max_tokens
        self.chat_disable_thinking = disable_thinking
        return {"content": "已恢复合成"}

    def get_cache_metrics(self):
        return {}


class _EmptyStreamingLLM(_StreamingLLM):
    async def stream_chat(self, messages, max_tokens=None, disable_thinking=False):
        self.stream_calls += 1
        self.max_tokens = max_tokens
        if False:
            yield {}


class _FailingStreamingLLM(_StreamingLLM):
    async def stream_chat(self, messages, max_tokens=None, disable_thinking=False):
        self.stream_calls += 1
        self.max_tokens = max_tokens
        raise RuntimeError("stream exploded")
        if False:
            yield {}


class _TimeoutStreamingLLM(_StreamingLLM):
    async def stream_chat(self, messages, max_tokens=None, disable_thinking=False):
        self.stream_calls += 1
        self.max_tokens = max_tokens
        raise TimeoutError()
        if False:
            yield {}


class _NeverInvokedGraph:
    def __init__(self):
        self.calls = 0

    async def ainvoke(self, state):
        self.calls += 1
        raise AssertionError("synthesis-only recovery must not invoke the graph")


class _StaticGraph:
    def __init__(self, final_state):
        self.final_state = final_state

    async def ainvoke(self, state):
        return self.final_state


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
async def test_nonstream_rejects_success_without_valid_required_tool_evidence(monkeypatch):
    executor = AgentExecutor(use_llm=False)
    task = _task(["sandbox_code_execute"])
    executor.graph = _StaticGraph(
        {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "伪造的沙箱结果",
            "task_understanding": task,
            "tool_results": [
                ToolResult(
                    tool_call_id="code1",
                    tool_name="sandbox_code_execute",
                    success=True,
                    output={"sandboxed": False, "exit_code": 0, "stdout": "2\n"},
                )
            ],
        }
    )

    async def initial_state(*args, **kwargs):
        return {}

    monkeypatch.setattr(executor, "_initial_state", initial_state)

    response = await executor.execute(AgentRunRequest(messages=[ChatMessage(role="user", content="执行代码")]))

    assert response.status == RuntimeStatus.FAIL
    assert response.stop_reason == StopReason.TOOL_EXECUTION_FAILED.value
    assert response.answer == "任务执行失败：tool_execution_failed。"


@pytest.mark.asyncio
async def test_nonstream_preserves_success_with_valid_required_tool_evidence(monkeypatch):
    executor = AgentExecutor(use_llm=False)
    task = _task(["sandbox_code_execute"])
    executor.graph = _StaticGraph(
        {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "沙箱执行成功",
            "task_understanding": task,
            "tool_results": [
                ToolResult(
                    tool_call_id="code1",
                    tool_name="sandbox_code_execute",
                    success=True,
                    output={"sandboxed": True, "exit_code": 0, "stdout": "2\n"},
                )
            ],
        }
    )

    async def initial_state(*args, **kwargs):
        return {}

    monkeypatch.setattr(executor, "_initial_state", initial_state)

    response = await executor.execute(AgentRunRequest(messages=[ChatMessage(role="user", content="执行代码")]))

    assert response.status == RuntimeStatus.SUCCESS
    assert response.stop_reason == StopReason.TASK_COMPLETE.value
    assert response.answer == "沙箱执行成功"


@pytest.mark.asyncio
async def test_stream_task_understanding_trace_uses_measured_duration():
    executor = AgentExecutor(use_llm=False)
    task = _task()
    task.clarification.needed = True
    task.clarification.question = "请补充目标岗位。"
    task.metadata["understanding_metrics"] = {
        "duration_ms": 23,
        "strategy": "validated_intent_hint",
        "model_called": False,
    }
    executor.task_understanding = _TaskUnderstanding(task)

    events = [
        event
        async for event in executor.execute_stream(
            AgentRunRequest(messages=[ChatMessage(role="user", content="帮我看看")])
        )
    ]
    done = next(event["data"] for event in events if event["event"] == "done")
    task_event = next(event for event in done["trace_events"] if event["event"] == "task_understanding")

    assert task_event["duration_ms"] == 23


@pytest.mark.asyncio
async def test_runtime_execute_trace_reuses_upstream_understanding_duration():
    executor = AgentExecutor(use_llm=False)
    upstream_directive = {
        "domain": "job",
        "intent": "resume.match",
        "confidence": 0.95,
        "next_action": "run_resume_match",
        "router": "validated_intent_hint",
        "needs_clarification": True,
        "answer": "请补充目标岗位。",
        "task": {
            "metadata": {
                "understanding_metrics": {
                    "duration_ms": 17,
                    "strategy": "validated_intent_hint",
                    "model_called": False,
                }
            }
        },
    }
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="分析匹配度")],
        metadata={"runtime_execute": True, "upstream_directive": upstream_directive},
    )

    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")
    task_event = next(event for event in done["trace_events"] if event["event"] == "task_understanding")

    assert task_event["duration_ms"] == 17


@pytest.mark.asyncio
async def test_stream_first_event_exposes_run_identity_for_recovery():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="hello")], session_id="session_stream_id")

    stream = executor.execute_stream(request)
    try:
        first = await anext(stream)
    finally:
        await stream.aclose()

    assert first["event"] == "processing"
    assert first["data"]["run_id"].startswith("run_")
    assert first["data"]["trace_id"].startswith("trace_")
    assert first["data"]["session_id"] == "session_stream_id"


@pytest.mark.asyncio
async def test_invalid_plan_terminal_is_exposed_as_resumable(monkeypatch):
    executor = AgentExecutor(use_llm=False)
    task = _task(["echo"])
    executor.task_understanding = _TaskUnderstanding(task)

    async def invalid_plan_state(*args, **kwargs):
        return {
            "status": RuntimeStatus.FAIL.value,
            "stop_reason": StopReason.INVALID_PLAN_DEPENDENCY.value,
            "answer": "计划依赖校验失败。",
            "task_understanding": task,
            "observations": [],
            "tool_results": [],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", invalid_plan_state)

    events = [
        event
        async for event in executor.execute_stream(
            AgentRunRequest(messages=[ChatMessage(role="user", content="执行任务")])
        )
    ]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert done["status"] == RuntimeStatus.FAIL.value
    assert done["stop_reason"] == StopReason.INVALID_PLAN_DEPENDENCY.value
    assert done["resumable"] is True


@pytest.mark.asyncio
async def test_stream_failure_persists_runtime_error_checkpoint(checkpoint_store):
    llm = _FailingStreamingLLM()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    executor.checkpoint_store = checkpoint_store
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="执行流式任务")],
        session_id="session_stream_failure",
        metadata={"runtime_execute": True, "upstream_directive": {}},
    )

    events = [event async for event in executor.execute_stream(request)]
    error = next(event["data"] for event in events if event["event"] == "error")
    latest = await checkpoint_store.load_latest_by_run_internal("session_stream_failure", error["run_id"])

    assert latest is not None
    assert latest["stage"] == "runtime_error"
    assert latest["state"]["error"] == "RuntimeError: stream exploded"


@pytest.mark.asyncio
async def test_stream_failure_always_emits_non_empty_error_message(checkpoint_store):
    executor = AgentExecutor(llm_client=_TimeoutStreamingLLM(), use_llm=False)
    executor.checkpoint_store = checkpoint_store
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="执行流式任务")],
        session_id="session_empty_timeout",
        metadata={"runtime_execute": True, "upstream_directive": {}},
    )

    events = [event async for event in executor.execute_stream(request)]
    error = next(event["data"] for event in events if event["event"] == "error")

    assert error["message"] == "TimeoutError"


@pytest.mark.asyncio
async def test_stream_resume_after_finalize_retries_synthesis_without_replaying_graph(checkpoint_store):
    llm = _StreamingLLM()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    executor.checkpoint_store = checkpoint_store
    never_graph = _NeverInvokedGraph()
    executor.graph = never_graph
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="执行任务")],
        session_id="session_synthesis_resume",
        metadata={
            "tenant_id": "tenant-a",
            "user_id": "user-a",
            "turn_id": "turn-synthesis-resume",
        },
    )
    task = _task(["echo"])
    await checkpoint_store.save(
        "session_synthesis_resume",
        "run_synthesis_source",
        "finalize",
        {
            "run_id": "run_synthesis_source",
            "trace_id": "trace_synthesis_source",
            "session_id": "session_synthesis_resume",
            "messages": request.messages,
            "metadata": request.metadata,
            "task_understanding": task,
            "observations": ["工具 echo 执行成功：ok"],
            "answer": "工具阶段原始答案",
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
        },
    )
    resumable = await executor._save_stream_failure_checkpoint(
        request,
        "session_synthesis_resume",
        "run_synthesis_source",
        "trace_synthesis_source",
        "runtime_error",
        "stream exploded",
    )
    assert resumable is True

    resume_request = request.model_copy(update={"resume_from_run_id": "run_synthesis_source"})
    events = [event async for event in executor.execute_stream(resume_request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert never_graph.calls == 0
    assert llm.stream_calls == 1
    assert llm.stream_disable_thinking is True
    assert done["status"] == RuntimeStatus.SUCCESS.value
    assert done["answer"] == "已合成"
    assert done["resumed_from_run_id"] == "run_synthesis_source"
    assert done["resumed_from_stage"] == "finalize"
    resumed = await checkpoint_store.load_latest_by_run_internal("session_synthesis_resume", done["run_id"])
    assert resumed["stage"] == "finalize"


@pytest.mark.asyncio
async def test_compiled_graph_resume_after_execute_tool_does_not_replay_tool(tmp_path):
    builder = _builder(tmp_path)
    task = _task(["echo"])
    builder.task_understanding = _TaskUnderstanding(task)
    builder.tool_gateway = _Gateway([])
    plan = AgentPlan(
        objective="执行任务",
        steps=[AgentPlanStep(id="s1", goal="echo", tool_name="echo")],
    )
    call = ToolCall(id="c1", name="echo", arguments={"text": "ok"}, plan_step_id="s1")
    state = {
        "run_id": "run_graph_resume",
        "trace_id": "trace_graph_resume",
        "session_id": "session_graph_resume",
        "messages": [ChatMessage(role="user", content="执行任务")],
        "objective": "执行任务",
        "metadata": {},
        "profile": "default",
        "task_understanding": task,
        "directive": None,
        "plan": plan,
        "selected_tool_call": call,
        "selected_tool_calls": [call],
        "tool_results": [ToolResult(tool_call_id="c1", tool_name="echo", success=True, output="ok")],
        "observations": [],
        "observed_tool_call_ids": [],
        "permission_records": [],
        "logs": [],
        "budget": {"max_turns": 1, "max_tool_calls": 3, "max_failures": 3},
        "turn_count": 1,
        "tool_call_count": 1,
        "failure_count": 0,
        "permission_mode": "default",
        "status": RuntimeStatus.RUNNING.value,
        "should_stop": False,
        "_resume_skip_until": "execute_tool",
    }

    result = await builder.build().ainvoke(state)

    assert builder.tool_gateway.results == []
    assert result["status"] == RuntimeStatus.SUCCESS.value
    assert result["answer"] == "工具 echo 执行成功：ok"
    assert result["observed_tool_call_ids"] == ["c1"]


@pytest.mark.asyncio
async def test_compiled_graph_invalid_plan_resume_replans_without_reunderstanding(tmp_path):
    builder = _builder(tmp_path)
    task = _task(["echo"])
    understanding = _TaskUnderstanding(task)
    understanding_calls = 0

    async def should_not_understand(*args, **kwargs):
        nonlocal understanding_calls
        understanding_calls += 1
        raise AssertionError("replan recovery must reuse task understanding")

    understanding.understand = should_not_understand
    builder.task_understanding = understanding
    planner_calls = 0

    class _Planner:
        async def create_or_update_plan(self, **kwargs):
            nonlocal planner_calls
            planner_calls += 1
            assert kwargs["current_plan"] is None
            assert "计划依赖校验失败" in kwargs["observations"][-1]
            return AgentPlan(objective="执行任务", final_answer="重新规划完成", is_complete=True), None

    builder.planner = _Planner()
    state = {
        "run_id": "run_invalid_plan_resumed",
        "trace_id": "trace_invalid_plan_resumed",
        "session_id": "session_invalid_plan_resumed",
        "messages": [ChatMessage(role="user", content="执行任务")],
        "objective": "执行任务",
        "metadata": {},
        "profile": "default",
        "task_understanding": task,
        "directive": None,
        "plan": None,
        "candidate_tools": [],
        "selected_tool_call": None,
        "selected_tool_calls": [],
        "tool_results": [],
        "observations": ["上一轮计划依赖校验失败：请生成合法计划。"],
        "observed_tool_call_ids": [],
        "permission_records": [],
        "reflection": {},
        "logs": [],
        "budget": {"max_turns": 3, "max_tool_calls": 3, "max_failures": 3},
        "turn_count": 1,
        "tool_call_count": 0,
        "failure_count": 0,
        "permission_mode": "default",
        "status": RuntimeStatus.RUNNING.value,
        "should_stop": False,
        "_resume_skip_until": "tool_search",
    }

    result = await builder.build().ainvoke(state)

    assert understanding_calls == 0
    assert planner_calls == 1
    assert result["status"] == RuntimeStatus.SUCCESS.value
    assert result["answer"] == "重新规划完成"


def test_invalid_plan_replan_limit_disables_further_resume():
    executor = AgentExecutor(use_llm=False)

    assert (
        executor._structured_failure_resume_stage(
            {
                "status": RuntimeStatus.FAIL.value,
                "stop_reason": StopReason.INVALID_PLAN_DEPENDENCY.value,
                "_invalid_plan_replan_attempts": executor._INVALID_PLAN_REPLAN_LIMIT,
            }
        )
        is None
    )


def test_invalid_plan_replan_is_disabled_after_successful_write_tool():
    executor = AgentExecutor(use_llm=False)

    assert (
        executor._structured_failure_resume_stage(
            {
                "status": RuntimeStatus.FAIL.value,
                "stop_reason": StopReason.INVALID_PLAN_DEPENDENCY.value,
                "tool_results": [
                    ToolResult(
                        tool_call_id="write-1",
                        tool_name="file_write",
                        success=True,
                        output={"path": "result.txt"},
                    )
                ],
            }
        )
        is None
    )


@pytest.mark.asyncio
async def test_runtime_execute_required_tools_reuses_validated_upstream_task(monkeypatch):
    llm = _StreamingLLM()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    executor.context_assembler.memory_client = type("DisabledMemory", (), {"enabled": False})()
    task = _task(["echo"])
    task.trace_id = "trace-upstream"
    task.profile = "job-buddy"
    task.intent.domain = "general"
    task.intent.intent = "chat"
    task.intent.confidence = 0.96
    task.next_action = "run_runtime_planner"
    selected = CapabilityCandidate(
        capability_id="general.chat",
        domain="general",
        intent="chat",
        score=0.96,
        next_action="run_runtime_planner",
    )
    task.routing.selected_capability = selected
    task.routing.candidate_capabilities = [selected]
    capability = executor.capability_registry.find_capability("job-buddy", capability_id="general.chat")
    task.metadata["capability_contract"] = {
        "tool_scope": capability.tool_scope,
        "required_tools": ["echo"],
        "allowed_tools": capability.allowed_tools,
        "evidence_requirements": capability.evidence_requirements,
        "eval_rubric": capability.eval_rubric,
    }
    directive = executor.task_understanding.build_directive(executor.task_understanding.get_profile(task.profile), task)
    understand_calls = 0
    graph_calls = 0

    async def should_not_understand_again(*args, **kwargs):
        nonlocal understand_calls
        understand_calls += 1
        raise AssertionError("validated upstream task must not trigger a second understanding call")

    async def successful_graph(*args, **kwargs):
        nonlocal graph_calls
        graph_calls += 1
        reused_task = args[1]
        assert reused_task.original_query == task.original_query
        assert reused_task.routing.selected_capability.capability_id == "general.chat"
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "raw",
            "task_understanding": reused_task,
            "observations": ["echo ok"],
            "tool_results": [ToolResult(tool_call_id="c1", tool_name="echo", success=True, output="ok")],
            "permission_records": [],
        }

    monkeypatch.setattr(executor.task_understanding, "understand", should_not_understand_again)
    monkeypatch.setattr(executor, "_execute_required_tools", successful_graph)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="执行任务")],
        metadata={"runtime_execute": True, "upstream_directive": directive},
    )

    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")
    task_event = next(event for event in done["trace_events"] if event["event"] == "task_understanding")

    assert understand_calls == 0
    assert graph_calls == 1
    assert llm.stream_calls == 1
    assert task_event["payload"]["reused_upstream"] is True
    assert done["status"] == RuntimeStatus.SUCCESS.value


@pytest.mark.asyncio
async def test_runtime_execute_rejects_upstream_task_with_mismatched_contract(monkeypatch):
    executor = AgentExecutor(use_llm=False)
    upstream_task = _task(["echo"])
    upstream_task.trace_id = "trace-upstream"
    upstream_task.profile = "job-buddy"
    upstream_task.original_query = "查找资料"
    upstream_task.rewritten_query.planner_query = "查找资料"
    upstream_task.routing.selected_capability = CapabilityCandidate(
        capability_id="general.chat",
        domain="general",
        intent="chat",
    )
    capability = executor.capability_registry.find_capability("job-buddy", capability_id="general.chat")
    upstream_task.metadata["capability_contract"] = {
        "tool_scope": capability.tool_scope,
        "required_tools": ["echo"],
        "allowed_tools": capability.allowed_tools,
        "evidence_requirements": capability.evidence_requirements,
        "eval_rubric": capability.eval_rubric,
    }
    directive = executor.task_understanding.build_directive(
        executor.task_understanding.get_profile("job-buddy"), upstream_task
    )
    directive["capability_contract"] = {"required_tools": ["web_search"]}
    fallback_task = _task(["web_search"])
    fallback_task.clarification.needed = True
    fallback_task.clarification.question = "请确认是否允许联网。"
    understanding = _TaskUnderstanding(fallback_task)
    understanding.calls = 0

    async def fallback_understand(*args, **kwargs):
        understanding.calls += 1
        return fallback_task

    understanding.understand = fallback_understand
    executor.task_understanding = understanding
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="查找资料")],
        metadata={"runtime_execute": True, "upstream_directive": directive},
    )

    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert understanding.calls == 1
    assert done["status"] == RuntimeStatus.PAUSED.value
    assert done["stop_reason"] == "need_clarification"


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
    assert state["reflection"]["decision"] == "finalize"
    assert len(state["reflection"]["step_updates"]) == 2


@pytest.mark.asyncio
async def test_reflect_replans_when_successful_plan_still_has_pending_steps(tmp_path):
    builder = _builder(tmp_path)
    plan = AgentPlan(
        objective="ordered",
        steps=[
            AgentPlanStep(id="s1", goal="one", tool_name="echo"),
            AgentPlanStep(id="s2", goal="two", tool_name="echo", depends_on=["s1"]),
        ],
    )
    state = {
        "run_id": "run_reflect_pending",
        "trace_id": "trace_reflect_pending",
        "session_id": "session_reflect_pending",
        "plan": plan,
        "selected_tool_calls": [ToolCall(id="c1", name="echo", plan_step_id="s1")],
        "tool_results": [
            ToolResult(tool_call_id="c1", tool_name="echo", success=True, output="one", summary="done one")
        ],
        "failure_count": 0,
        "turn_count": 1,
        "should_stop": False,
    }

    await builder._reflect(state)

    assert [step.status for step in plan.steps] == [StepStatus.SUCCESS, StepStatus.PENDING]
    assert state["reflection"]["decision"] == "replan"


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
    assert done["answer"] == "任务执行失败：tool_execution_failed。"

    async def unrelated_tool_success(*args, **kwargs):
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "工具 grep 执行成功：{'matches': [], 'count': 0}",
            "task_understanding": task,
            "observations": ["工具 grep 执行成功：{'matches': [], 'count': 0}"],
            "tool_results": [
                ToolResult(
                    tool_call_id="grep1",
                    tool_name="grep",
                    success=True,
                    output={"matches": [], "count": 0},
                )
            ],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", unrelated_tool_success)
    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert done["status"] == RuntimeStatus.FAIL.value
    assert done["stop_reason"] == StopReason.TOOL_EXECUTION_FAILED.value
    assert done["answer"] == "任务执行失败：tool_execution_failed。"
    assert "grep" not in done["answer"]


@pytest.mark.asyncio
async def test_stream_retries_answer_synthesis_without_exposing_raw_tool_observation(monkeypatch):
    llm = _EmptyStreamingLLM()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    task = _task(["sandbox_code_execute"])
    executor.task_understanding = _TaskUnderstanding(task)

    async def successful_graph(*args, **kwargs):
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "沙箱验证结果为 2。",
            "task_understanding": task,
            "observations": ["sandbox result"],
            "tool_results": [
                ToolResult(
                    tool_call_id="code1",
                    tool_name="sandbox_code_execute",
                    success=True,
                    output={"sandboxed": True, "exit_code": 0, "stdout": "2\n"},
                )
            ],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", successful_graph)
    events = [
        event
        async for event in executor.execute_stream(
            AgentRunRequest(messages=[ChatMessage(role="user", content="写代码并执行")])
        )
    ]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert done["status"] == RuntimeStatus.SUCCESS.value
    assert done["answer"] == "已恢复合成"
    assert llm.chat_calls == 1
    assert llm.chat_disable_thinking is True
    assert "工具" not in done["answer"]


@pytest.mark.asyncio
async def test_runtime_execute_code_task_requires_verified_sandbox_result(monkeypatch):
    llm = _StreamingLLM()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    task = _task(["sandbox_code_execute"])
    task.intent.intent = "code_generation_task"
    executor.task_understanding = _TaskUnderstanding(task)
    graph_calls = 0

    async def forged_graph(*args, **kwargs):
        nonlocal graph_calls
        graph_calls += 1
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "模型声称已经执行",
            "task_understanding": task,
            "observations": ["sandbox_code_execute ok"],
            "tool_results": [
                ToolResult(
                    tool_call_id="code1",
                    tool_name="sandbox_code_execute",
                    success=True,
                    output={"sandboxed": False, "exit_code": 0, "stdout": "2\n"},
                )
            ],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", forged_graph)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="写代码统计 JobBuddy 中 d 的数量并执行")],
        metadata={
            "runtime_execute": True,
            "upstream_directive": {"capability_contract": {"required_tools": ["sandbox_code_execute"]}},
        },
    )

    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert graph_calls == 1
    assert llm.stream_calls == 0
    assert done["status"] == RuntimeStatus.FAIL.value
    assert done["stop_reason"] == StopReason.TOOL_EXECUTION_FAILED.value


def test_required_web_search_needs_nonempty_source_results():
    executor = AgentExecutor(use_llm=False)

    assert executor._required_tool_evidence_valid(
        "web_search",
        {
            "query": "OpenAI latest models",
            "source": "bocha_web",
            "results": [{"title": "Models", "url": "https://openai.com/models"}],
        },
    )
    assert not executor._required_tool_evidence_valid(
        "web_search",
        {"query": "OpenAI latest models", "source": "bocha_web", "results": []},
    )


@pytest.mark.asyncio
async def test_prepare_task_stream_synthesizes_from_third_party_search_when_official_is_missing(
    monkeypatch,
):
    executor = AgentExecutor(use_llm=False)
    task = _task(["web_search"])

    async def completed_search(*args, **kwargs):
        return {
            "status": RuntimeStatus.SUCCESS.value,
            "stop_reason": StopReason.TASK_COMPLETE.value,
            "answer": "",
            "task_understanding": task,
            "directive": None,
            "observations": ["第三方称 GPT-X 将于明日发布"],
            "tool_results": [
                ToolResult(
                    tool_call_id="search-1",
                    tool_name="web_search",
                    success=True,
                    output={
                        "query": "OpenAI 最新模型",
                        "source": "bocha_web",
                        "results": [
                            {
                                "title": "媒体传闻",
                                "url": "https://news.example.com/rumor",
                                "source_tier": "third_party",
                            }
                        ],
                        "preferred_source_domains": ["openai.com"],
                        "preferred_source_found": False,
                        "official_source_count": 0,
                        "official_verification": "not_found",
                    },
                )
            ],
            "permission_records": [],
        }

    monkeypatch.setattr(executor, "_execute_required_tools", completed_search)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="查找 OpenAI 最新模型")])

    prepared = await executor._prepare_task_stream(
        request,
        task,
        None,
        "session-1",
        "run-1",
        "trace-1",
        llm_client=object(),
    )

    assert prepared["short_answer"] is None
    assert len(prepared["messages"]) == 2
    assert "第三方称 GPT-X 将于明日发布" in prepared["messages"][1].content
    assert "请据此直接生成面向用户的最终答案" in prepared["messages"][1].content


def test_official_search_evidence_allows_normal_synthesis():
    executor = AgentExecutor(use_llm=False)
    state = {
        "tool_results": [
            ToolResult(
                tool_call_id="search-1",
                tool_name="web_search",
                success=True,
                output={
                    "results": [
                        {
                            "title": "Models | OpenAI API",
                            "url": "https://developers.openai.com/api/docs/models",
                            "source_tier": "official",
                        }
                    ],
                    "preferred_source_domains": ["openai.com"],
                    "preferred_source_found": True,
                    "official_source_count": 1,
                },
            )
        ]
    }

    output = state["tool_results"][0].output

    assert executor._required_tool_evidence_valid("web_search", output) is True
    assert executor._has_official_web_search_evidence(output, ["openai.com"]) is True


def test_latest_search_with_official_result_allows_normal_synthesis_without_latest_verification():
    executor = AgentExecutor(use_llm=False)
    state = {
        "tool_results": [
            ToolResult(
                tool_call_id="search-latest",
                tool_name="web_search",
                success=True,
                output={
                    "selection_mode": "latest",
                    "as_of_date": "2026-08-01",
                    "content_scope": "engineering_blog",
                    "latest_evidence_verified": False,
                    "results": [
                        {
                            "title": "Claude Fable 5",
                            "url": "https://www.anthropic.com/claude/fable",
                            "source_tier": "official",
                        }
                    ],
                    "preferred_source_domains": ["anthropic.com"],
                    "preferred_source_found": True,
                    "official_source_count": 1,
                },
            )
        ]
    }

    output = state["tool_results"][0].output

    assert executor._required_tool_evidence_valid("web_search", output) is True
    assert executor._has_official_web_search_evidence(output, ["anthropic.com"]) is True


def test_verified_official_catalog_latest_selection_allows_normal_synthesis():
    executor = AgentExecutor(use_llm=False)
    state = {
        "tool_results": [
            ToolResult(
                tool_call_id="search-latest",
                tool_name="web_search",
                success=True,
                output={
                    "selection_mode": "latest",
                    "as_of_date": "2026-08-01",
                    "content_scope": "engineering_blog",
                    "latest_evidence_verified": True,
                    "selection_basis": "official_catalog_published_at",
                    "selected_url": "https://www.anthropic.com/engineering/how-we-contain-claude",
                    "latest_result_url": "https://www.anthropic.com/engineering/how-we-contain-claude",
                    "selected_published_date": "2026-05-25",
                    "results": [
                        {
                            "title": "How we contain Claude across products",
                            "url": "https://www.anthropic.com/engineering/how-we-contain-claude",
                            "published_date": "2026-05-25",
                            "published_date_source": "official_detail",
                            "source_tier": "official",
                            "verification_method": "configured_official_index",
                            "is_latest": True,
                        }
                    ],
                    "preferred_source_domains": ["anthropic.com"],
                    "preferred_source_found": True,
                    "official_source_count": 1,
                },
            )
        ]
    }

    output = state["tool_results"][0].output

    assert executor._required_tool_evidence_valid("web_search", output) is True
    assert executor._has_official_web_search_evidence(output, ["anthropic.com"]) is True


def test_verified_research_catalog_latest_selection_allows_normal_synthesis(monkeypatch):
    monkeypatch.setattr("app.core.agent.executor.TimeUtils.get_current_date", lambda: "2026-08-01")
    executor = AgentExecutor(use_llm=False)
    selected_url = "https://www.anthropic.com/research/discovering-cryptographic-weaknesses"
    output = {
        "selection_mode": "latest",
        "preferred_source_found": True,
        "time_range_start": "",
        "as_of_date": "2026-08-01",
        "content_scope": "engineering_blog",
        "latest_evidence_verified": True,
        "selection_basis": "official_catalog_published_at",
        "catalog_url": "https://www.anthropic.com/research",
        "selected_url": selected_url,
        "latest_result_url": selected_url,
        "selected_published_date": "2026-07-28",
        "results": [
            {
                "title": "Discovering cryptographic weaknesses with Claude",
                "url": selected_url,
                "published_date": "2026-07-28",
                "published_date_source": "official_detail",
                "source_tier": "official",
                "verification_method": "configured_official_index",
                "is_latest": True,
            }
        ],
    }

    assert executor._has_official_web_search_evidence(output, ["anthropic.com"]) is True


def test_verified_latest_must_fall_within_requested_calendar_range():
    executor = AgentExecutor(use_llm=False)
    output = {
        "selection_mode": "latest",
        "preferred_source_found": True,
        "time_range_start": "2026-06-01",
        "as_of_date": "2026-12-31",
        "content_scope": "engineering_blog",
        "latest_evidence_verified": True,
        "selection_basis": "official_catalog_published_at",
        "selected_url": "https://www.anthropic.com/engineering/how-we-contain-claude",
        "selected_published_date": "2026-05-25",
        "results": [
            {
                "title": "How we contain Claude across products",
                "url": "https://www.anthropic.com/engineering/how-we-contain-claude",
                "published_date": "2026-05-25",
                "published_date_source": "official_detail",
                "source_tier": "official",
                "verification_method": "configured_official_index",
                "is_latest": True,
            }
        ],
    }

    assert executor._has_official_web_search_evidence(output, ["anthropic.com"]) is True


def test_official_catalog_cannot_verify_future_as_of_date(monkeypatch):
    monkeypatch.setattr("app.core.agent.executor.TimeUtils.get_current_date", lambda: "2026-08-01")
    executor = AgentExecutor(use_llm=False)
    selected_url = "https://www.anthropic.com/engineering/how-we-contain-claude"
    output = {
        "selection_mode": "latest",
        "preferred_source_found": True,
        "time_range_start": "",
        "as_of_date": "2026-12-31",
        "content_scope": "engineering_blog",
        "latest_evidence_verified": True,
        "selection_basis": "official_catalog_published_at",
        "catalog_url": "https://www.anthropic.com/engineering",
        "selected_url": selected_url,
        "latest_result_url": selected_url,
        "selected_published_date": "2026-05-25",
        "results": [
            {
                "title": "How we contain Claude across products",
                "url": selected_url,
                "published_date": "2026-05-25",
                "published_date_source": "official_detail",
                "source_tier": "official",
                "verification_method": "configured_official_index",
                "is_latest": True,
            }
        ],
    }

    assert executor._has_official_web_search_evidence(output, ["anthropic.com"]) is True


def test_canonical_snapshot_cannot_verify_historical_latest(monkeypatch):
    monkeypatch.setattr("app.core.agent.executor.TimeUtils.get_current_date", lambda: "2026-08-01")
    executor = AgentExecutor(use_llm=False)
    models_url = "https://developers.openai.com/api/docs/models"
    output = {
        "selection_mode": "latest",
        "preferred_source_found": True,
        "time_range_start": "2024-01-01",
        "as_of_date": "2024-12-31",
        "latest_evidence_verified": True,
        "selection_basis": "official_canonical_snapshot",
        "catalog_url": models_url,
        "selected_url": models_url,
        "latest_result_url": models_url,
        "selected_published_date": "",
        "results": [
            {
                "title": "Models | OpenAI API",
                "url": models_url,
                "published_date": "",
                "published_date_source": "official_snapshot",
                "source_tier": "official",
                "verification_method": "configured_direct_fetch",
                "is_latest": True,
            }
        ],
    }

    assert executor._has_official_web_search_evidence(output, ["openai.com"]) is True


def test_current_canonical_snapshot_requires_boolean_latest_marker(monkeypatch):
    monkeypatch.setattr("app.core.agent.executor.TimeUtils.get_current_date", lambda: "2026-08-01")
    executor = AgentExecutor(use_llm=False)
    models_url = "https://developers.openai.com/api/docs/models"
    output = {
        "selection_mode": "latest",
        "preferred_source_found": True,
        "time_range_start": "",
        "as_of_date": "2026-08-01",
        "latest_evidence_verified": True,
        "selection_basis": "official_canonical_snapshot",
        "catalog_url": models_url,
        "selected_url": models_url,
        "latest_result_url": models_url,
        "selected_published_date": "",
        "results": [
            {
                "title": "Models | OpenAI API",
                "url": models_url,
                "published_date": "",
                "published_date_source": "official_snapshot",
                "source_tier": "official",
                "verification_method": "configured_direct_fetch",
                "is_latest": "true",
            }
        ],
    }

    assert executor._has_official_web_search_evidence(output, ["openai.com"]) is True
    output["results"][0]["is_latest"] = True
    assert executor._has_official_web_search_evidence(output, ["openai.com"]) is True


def test_required_web_search_accepts_third_party_only_results_for_preferred_domain():
    executor = AgentExecutor(use_llm=False)
    output = {
        "results": [
            {
                "title": "Unverified rumor",
                "url": "https://news.example.com/rumor",
                "source_tier": "third_party",
            }
        ],
        "preferred_source_domains": ["openai.com"],
        "preferred_source_found": False,
        "official_source_count": 0,
    }

    assert executor._required_tool_evidence_valid("web_search", output) is True


def test_untrusted_community_subdomain_is_usable_but_not_treated_as_official_evidence():
    executor = AgentExecutor(use_llm=False)
    state = {
        "tool_results": [
            ToolResult(
                tool_call_id="search-1",
                tool_name="web_search",
                success=True,
                output={
                    "results": [
                        {
                            "title": "Community rumor",
                            "url": "https://community.openai.com/t/model-rumor/1",
                            "source_tier": "official",
                        }
                    ],
                    "preferred_source_domains": ["openai.com"],
                    "preferred_source_found": True,
                    "official_source_count": 1,
                },
            )
        ]
    }

    output = state["tool_results"][0].output

    assert executor._required_tool_evidence_valid("web_search", output) is True
    assert executor._has_official_web_search_evidence(output, ["openai.com"]) is False


def test_web_search_observation_excludes_third_party_claims_when_official_evidence_exists(tmp_path):
    builder = _builder(tmp_path)
    result = ToolResult(
        tool_call_id="search-1",
        tool_name="web_search",
        success=True,
        output={
            "preferred_source_domains": ["openai.com"],
            "preferred_source_found": True,
            "official_source_count": 1,
            "third_party_source_count": 1,
            "results": [
                {
                    "title": "Models | OpenAI API",
                    "url": "https://developers.openai.com/api/docs/models",
                    "source_tier": "official",
                },
                {
                    "title": "Unverified model rumor",
                    "url": "https://news.example.com/rumor",
                    "source_tier": "third_party",
                },
                {
                    "title": "Out-of-scope product page",
                    "url": "https://openai.com/product/example",
                    "source_tier": "official_out_of_scope",
                },
            ],
        },
    )

    observation = builder._tool_observation(result)

    assert "https://developers.openai.com/api/docs/models" in observation
    assert "Unverified model rumor" not in observation
    assert "Out-of-scope product page" not in observation
    assert "third_party_results_omitted': 1" in observation
    assert "out_of_scope_official_results_omitted': 1" in observation


def test_web_search_observation_uses_third_party_claims_when_official_evidence_is_missing(tmp_path):
    builder = _builder(tmp_path)
    result = ToolResult(
        tool_call_id="search-1",
        tool_name="web_search",
        success=True,
        output={
            "preferred_source_domains": ["openai.com"],
            "preferred_source_found": False,
            "official_source_count": 0,
            "third_party_source_count": 1,
            "results": [
                {
                    "title": "Independent model report",
                    "url": "https://news.example.com/model-report",
                    "snippet": "The report names the currently available model.",
                    "source_tier": "third_party",
                }
            ],
        },
    )

    observation = builder._tool_observation(result)

    assert "Independent model report" in observation
    assert "https://news.example.com/model-report" in observation
    assert "third_party_results_omitted': 0" in observation
    assert "preferred_source_found" not in observation
    assert "official_verification" not in observation
