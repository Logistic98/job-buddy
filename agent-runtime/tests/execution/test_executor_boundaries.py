from hashlib import sha256
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.agent.executor import AgentExecutor
from app.models.schemas import AgentRunRequest, ChatMessage, ToolCall


@pytest.fixture
def executor(monkeypatch):
    monkeypatch.delenv("AGENT_RUNTIME_DATABASE_URL", raising=False)
    return AgentExecutor(use_llm=False)


@pytest.mark.asyncio
async def test_executor_close_releases_owned_clients_and_checkpoint_store(executor):
    executor.default_llm_client = SimpleNamespace(aclose=AsyncMock())
    executor.context_assembler.memory_client = SimpleNamespace(aclose=AsyncMock())
    executor.checkpoint_store = SimpleNamespace(close=AsyncMock())
    await executor.aclose()
    executor.default_llm_client.aclose.assert_awaited_once()
    executor.context_assembler.memory_client.aclose.assert_awaited_once()
    executor.checkpoint_store.close.assert_awaited_once()


@pytest.mark.parametrize("arguments", [{"text": "[REDACTED]"}, {"text": [{"redacted": True, "sha256": "digest"}]}])
def test_resume_rejects_read_tool_with_irrecoverably_redacted_arguments(executor, arguments):
    call = ToolCall(id="call", name="echo", arguments=arguments)
    with pytest.raises(ValueError, match="缺少工具完整输入"):
        executor._validate_resume_stage("plan", {"selected_tool_call": call})


@pytest.mark.parametrize("name", ["file_write", "missing"])
def test_resume_rejects_non_readonly_or_missing_tools(executor, name):
    with pytest.raises(ValueError, match="非只读工具"):
        executor._validate_resume_stage(
            "plan", {"selected_tool_calls": [{"id": "call", "name": name, "arguments": {}}]}
        )


def test_resume_allows_safe_completed_read_and_synthesis_only_stage(executor):
    executor._validate_resume_stage(
        "plan", {"selected_tool_call": ToolCall(id="call", name="echo", arguments={"text": "plain"})}
    )
    executor._validate_resume_stage("finalize", {"_resume_mode": "synthesis_only"})
    with pytest.raises(ValueError, match="阶段不可恢复"):
        executor._validate_resume_stage("finalize", {})


@pytest.mark.parametrize(
    ("change", "message"),
    [
        ({"messages": [ChatMessage(role="user", content="changed")]}, "用户消息"),
        ({"metadata": {"turn_id": "another"}}, "turnId"),
        ({"metadata": {"turn_id": "turn", "attachments": [{"attachmentId": "other"}]}}, "附件"),
    ],
)
def test_resume_requires_same_message_turn_and_attachments(executor, change, message):
    request = AgentRunRequest(
        **{"messages": [ChatMessage(role="user", content="original")], "metadata": {"turn_id": "turn"}, **change}
    )
    source = {"_resume_message_sha256": sha256(b"original").hexdigest(), "metadata": {"turn_id": "turn"}}
    with pytest.raises(ValueError, match=message):
        executor._validate_resume_request_context(request, source)


def test_resume_attachment_identity_is_order_independent_and_ignores_invalid_entries(executor):
    assert executor._attachment_ids(None) == ()
    assert executor._attachment_ids([None, {}, {"attachmentId": "b"}, {"attachment_id": "a"}]) == ("a", "b")
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="original")], metadata={"turn_id": "turn"})
    executor._validate_resume_request_context(request, {"metadata": {"turn_id": "turn"}})


@pytest.mark.parametrize(
    "state",
    [
        {"_invalid_plan_replan_attempts": "invalid"},
        {"tool_results": [{"unexpected": True}]},
        {"tool_results": [{"tool_call_id": "call", "tool_name": "file_write", "success": True}]},
    ],
)
def test_structured_failure_does_not_replay_invalid_or_mutating_state(executor, state):
    assert (
        executor._structured_failure_resume_stage({"status": "fail", "stop_reason": "invalid_plan_dependency", **state})
        is None
    )


@pytest.mark.parametrize(("status", "expected"), [("running", "running"), ("paused", "paused"), ("error", "failed")])
def test_trace_status_preserves_non_success_terminal_state(executor, status, expected):
    assert executor._trace_status(status) == expected


@pytest.mark.asyncio
@pytest.mark.parametrize("answer", ["Synthesized", " "])
async def test_nonstream_tool_success_requires_nonblank_synthesis(executor, monkeypatch, answer):
    client = SimpleNamespace(chat=AsyncMock(return_value={"content": answer}), aclose=AsyncMock())
    monkeypatch.setattr(executor, "_resolve_request_llm", lambda request: client)
    # Graph is the execution boundary; exercise executor synthesis and terminal handling.
    graph = SimpleNamespace(
        ainvoke=AsyncMock(
            return_value={
                "status": "success",
                "stop_reason": "task_complete",
                "tool_results": [],
                "observations": ["observed"],
            }
        )
    )
    from app.models.schemas import ToolResult

    graph.ainvoke.return_value["tool_results"] = [
        ToolResult(tool_call_id="call", tool_name="echo", success=True, output="observed")
    ]
    monkeypatch.setattr(executor, "_build_graph", lambda llm: graph)
    monkeypatch.setattr(
        executor, "_build_synthesis_messages", lambda *args: [ChatMessage(role="user", content="summarize")]
    )
    response = await executor.execute(AgentRunRequest(messages=[ChatMessage(role="user", content="hello")]))
    assert response.status.value == ("success" if answer.strip() else "fail")
    if answer.strip():
        assert response.answer == answer
    else:
        assert "未产出" in response.error
    client.aclose.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("use_model", [True, False])
async def test_synthesis_resume_saves_final_answer_without_running_graph(executor, use_model):
    from app.models.schemas import TaskUnderstandingResult

    request = AgentRunRequest(messages=[ChatMessage(role="user", content="goal")])
    state = {
        "task_understanding": TaskUnderstandingResult(original_query="goal"),
        "session_id": "session",
        "run_id": "run",
        "_resume_fallback_answer": "verified",
    }
    client = SimpleNamespace(chat=AsyncMock(return_value={"content": "synthesized"})) if use_model else None
    executor.graph = SimpleNamespace(ainvoke=AsyncMock())
    result = await executor._complete_synthesis_only_resume(request, state, client)
    assert result["answer"] == ("synthesized" if use_model else "verified")
    assert result["status"] == "success"
    executor.graph.ainvoke.assert_not_awaited()
    saved = await executor.checkpoint_store.load_latest_by_run_internal("session", "run")
    assert saved["stage"] == "finalize"


@pytest.mark.asyncio
@pytest.mark.parametrize("state", [{}, {"task_understanding": {}}])
async def test_synthesis_resume_rejects_missing_context_or_empty_answer(executor, state):
    with pytest.raises(ValueError, match="答案合成断点"):
        await executor._complete_synthesis_only_resume(AgentRunRequest(messages=[]), state, None)


def test_required_evidence_rejects_missing_sources_and_untrusted_hosts(executor):
    assert not executor._required_tool_evidence_valid("web_search", [])
    assert not executor._has_official_web_search_evidence({}, ["anthropic.com"])
    assert not executor._has_official_web_search_evidence(
        {
            "preferred_source_found": True,
            "results": [None, {"source_tier": "community"}, {"source_tier": "official", "url": "https://evil.invalid"}],
        },
        ["anthropic.com"],
    )
    assert executor._terminal_answer("need_confirm", "permission") == "任务需要确认后才能继续执行。"
    assert "已暂停" in executor._terminal_answer("paused", "budget")
    assert executor._understanding_duration_ms(None) is None


@pytest.mark.asyncio
async def test_stream_checkpoint_write_failure_still_produces_error_event(executor, monkeypatch):
    monkeypatch.setattr(executor, "_initial_state", AsyncMock(side_effect=ValueError("invalid checkpoint")))
    executor.checkpoint_store.save = AsyncMock(side_effect=OSError("storage offline"))
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="goal")], resume_from_run_id="prior")
    events = [item async for item in executor.execute_stream(request)]
    assert events[0]["event"] == "processing"
    assert events[-1]["event"] == "error"
    assert events[-1]["data"]["resumable"] is False
    assert "invalid checkpoint" in events[-1]["data"]["message"]


@pytest.mark.asyncio
async def test_stream_cancellation_saves_interruption_and_restores_context(executor, monkeypatch):
    import asyncio

    from app.core.observability.trace import current_trace_context

    monkeypatch.setattr(executor, "_initial_state", AsyncMock(side_effect=asyncio.CancelledError()))
    request = AgentRunRequest(messages=[], resume_from_run_id="prior", session_id="session")
    before = current_trace_context()
    with pytest.raises(asyncio.CancelledError):
        _ = [item async for item in executor.execute_stream(request)]
    assert current_trace_context() == before
    checkpoint = await executor.checkpoint_store.load_latest("session")
    assert checkpoint["stage"] == "interrupted"
    assert checkpoint["state"]["stop_reason"] == "stream_interrupted"


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["synthesis", "synthesis_no_model", "graph", "graph_no_model", "empty_stream"])
async def test_stream_resume_uses_graph_or_saved_synthesis_and_closes_request_client(executor, monkeypatch, mode):
    from app.models.schemas import TaskUnderstandingResult, ToolResult

    task = TaskUnderstandingResult(original_query="goal")
    state = {
        "task_understanding": task,
        "status": "success",
        "stop_reason": "task_complete",
        "answer": "verified",
        "observations": ["evidence"],
    }
    if mode.startswith("synthesis"):
        state.update(_resume_mode="synthesis_only", _resume_fallback_answer="verified")
    else:
        state["tool_results"] = [ToolResult(tool_call_id="read", tool_name="echo", success=True, output="evidence")]
    client = None
    if not mode.endswith("no_model"):

        async def stream(*args, **kwargs):
            yield {"text": ""}
            if mode != "empty_stream":
                yield {"type": "reasoning", "text": "reason"}
                yield {"text": "answer"}

        client = SimpleNamespace(
            stream_chat=stream, chat=AsyncMock(return_value={"content": "recovered"}), aclose=AsyncMock()
        )
    monkeypatch.setattr(executor, "_resolve_request_llm", lambda request: client)
    monkeypatch.setattr(executor, "_initial_state", AsyncMock(return_value=state))
    graph = SimpleNamespace(ainvoke=AsyncMock(return_value=state))
    monkeypatch.setattr(executor, "graph", graph)
    monkeypatch.setattr(executor, "_build_graph", lambda llm: graph)
    events = [
        event
        async for event in executor.execute_stream(
            AgentRunRequest(messages=[ChatMessage(role="user", content="goal")], resume_from_run_id="prior")
        )
    ]
    assert events[-1]["event"] == "done"
    tokens = "".join(event["data"]["text"] for event in events if event["event"] == "token")
    assert tokens == ("recovered" if mode == "empty_stream" else "verified" if mode.endswith("no_model") else "answer")
    assert graph.ainvoke.await_count == (0 if mode.startswith("synthesis") else 1)
    if client:
        client.aclose.assert_awaited_once()


@pytest.mark.asyncio
async def test_nonstream_failure_restores_only_current_run_checkpoint(executor, monkeypatch):
    async def fail(state):
        await executor.checkpoint_store.save(
            state["session_id"], state["run_id"], "plan", {**state, "observations": ["current evidence"]}
        )
        raise TimeoutError("deadline")

    executor.graph = SimpleNamespace(ainvoke=fail)
    response = await executor.execute(
        AgentRunRequest(messages=[ChatMessage(role="user", content="goal")], session_id="session")
    )
    assert response.status.value == "fail"
    assert "deadline" in response.error
    saved = await executor.checkpoint_store.load_latest_by_run_internal("session", response.run_id)
    assert saved["state"]["_resume_skip_until"] == "plan"
    assert saved["state"]["observations"] == ["current evidence"]


@pytest.mark.parametrize(
    "metadata",
    [
        {"upstream_directive": {"task": {"rewritten_query": {}}}},
        {"upstream_directive": {"task": {"rewritten_query": {"planner_query": " ", "resolved_query": " "}}}},
    ],
)
def test_upstream_empty_rewrite_does_not_become_query(executor, metadata):
    assert executor._upstream_planner_query(metadata) == ""
    assert executor._truthy("true")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("task_fields", "directive", "reason"),
    [
        ({"clarification": {"needed": True, "question": "Which?"}}, {}, "need_clarification"),
        ({"risk_flags": {"safety_blocked": True}}, {}, "safety_blocked"),
        ({}, {"answer": "static answer"}, "task_complete"),
    ],
)
async def test_stream_preparation_short_circuits_clarification_safety_and_declared_answer(
    executor, task_fields, directive, reason
):
    from app.models.schemas import TaskUnderstandingResult

    task = TaskUnderstandingResult(original_query="goal", **task_fields)
    result = await executor._prepare_task_stream(
        AgentRunRequest(messages=[]), task, directive, "session", "run", "trace"
    )
    assert result["stop_reason"] == reason
    assert result["short_answer"]
    assert result["messages"] == []


def test_zero_token_budget_uses_configured_limit_and_hydrates_selected_call(executor):
    from app.core.common.settings import settings

    request = AgentRunRequest(messages=[], budget={"max_tokens": 0})
    assert executor._effective_budget(request)["max_tokens"] == settings.config.runtime.max_run_tokens
    state = executor._hydrate_state(
        {"selected_tool_call": {"id": "call", "name": "echo", "arguments": {"text": "hello"}}}
    )
    assert state["selected_tool_call"].name == "echo"


@pytest.mark.asyncio
async def test_nonstream_synthesis_resume_bypasses_graph(executor, monkeypatch):
    from app.models.schemas import TaskUnderstandingResult

    state = {
        "_resume_mode": "synthesis_only",
        "_resume_fallback_answer": "verified",
        "task_understanding": TaskUnderstandingResult(original_query="goal"),
        "session_id": "session",
        "run_id": "run",
    }
    monkeypatch.setattr(executor, "_initial_state", AsyncMock(return_value=state))
    executor.graph = SimpleNamespace(ainvoke=AsyncMock())
    result = await executor.execute(AgentRunRequest(messages=[ChatMessage(role="user", content="goal")]))
    assert result.answer == "verified"
    executor.graph.ainvoke.assert_not_awaited()


@pytest.mark.asyncio
async def test_stream_generation_without_model_falls_back_to_nonstream(executor):
    events = [
        event
        async for event in executor.execute_stream(
            AgentRunRequest(messages=[ChatMessage(role="user", content="hello")], metadata={"runtime_execute": True})
        )
    ]
    assert events[-1]["event"] == "done"
    assert any(event["event"] == "token" for event in events)


@pytest.mark.asyncio
async def test_upstream_risk_answer_short_circuits_model(executor):
    request = AgentRunRequest(
        messages=[], metadata={"runtime_execute": True, "upstream_directive": {"risk": "high", "answer": "blocked"}}
    )
    events = [event async for event in executor.execute_stream(request)]
    assert events[-1]["event"] == "done"
    assert events[-1]["data"]["stop_reason"] == "safety_blocked"
    assert [event["data"]["text"] for event in events if event["event"] == "token"] == ["blocked"]


@pytest.mark.parametrize("invalid", ["schema", "query", "profile", "capability", "intent", "scope", "registry_error"])
def test_upstream_task_reuse_rejects_invalid_identity_and_authority(executor, invalid, monkeypatch):
    from app.models.schemas import TaskUnderstandingResult

    capability = executor.capability_registry.find_capability("job-buddy", capability_id="general.chat")
    task = TaskUnderstandingResult(
        original_query="goal",
        trace_id="trace",
        profile="job-buddy",
        intent={"domain": capability.domain, "intent": capability.intent},
        next_action=capability.next_action,
        routing={
            "selected_capability": {
                "capability_id": capability.id,
                "domain": capability.domain,
                "intent": capability.intent,
            }
        },
        metadata={
            "capability_contract": {
                "tool_scope": capability.tool_scope,
                "required_tools": capability.required_tools,
                "allowed_tools": capability.allowed_tools,
                "evidence_requirements": capability.evidence_requirements,
                "eval_rubric": capability.eval_rubric,
            }
        },
    )
    directive = executor.task_understanding.build_directive(executor.task_understanding.get_profile(task.profile), task)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="goal")])
    assert executor._validated_reusable_upstream_task(request, directive, executor.task_understanding) is not None
    if invalid == "registry_error":

        def unavailable(profile_id):
            raise OSError("configuration unavailable")

        monkeypatch.setattr(executor.capability_registry, "get_profile", unavailable)
    elif invalid == "schema":
        directive["task"]["intent"] = "invalid"
    elif invalid == "query":
        directive["task"]["original_query"] = "another"
    elif invalid == "profile":
        directive["task"]["profile"] = "nonexistent"
    elif invalid == "capability":
        directive["task"]["routing"]["selected_capability"]["capability_id"] = "missing"
    elif invalid == "intent":
        directive["intent"] = "another"
    else:
        for contract in [
            directive["capability_contract"],
            directive["task"]["metadata"]["capability_contract"],
            directive["task"]["routing"]["capability_contract"],
        ]:
            contract["allowed_tools"] = ["unauthorized"]
    assert executor._validated_reusable_upstream_task(request, directive, executor.task_understanding) is None


@pytest.mark.asyncio
@pytest.mark.parametrize("status", ["paused", "need_confirm"])
async def test_checkpoint_resume_cannot_bypass_paused_or_confirmation_state(executor, status):
    metadata = {"tenant_id": "tenant", "operator_id": "user", "turn_id": "turn"}
    await executor.checkpoint_store.save("session", "prior", "plan", {"status": status, "metadata": metadata})
    request = AgentRunRequest(messages=[], session_id="session", resume_from_run_id="prior", metadata=metadata)
    with pytest.raises(ValueError, match="暂停或待确认"):
        await executor._initial_state(request, "session", "next", "trace")


@pytest.mark.asyncio
async def test_checkpoint_resume_claim_prevents_duplicate_execution(executor):
    metadata = {"tenant_id": "tenant", "operator_id": "user", "turn_id": "turn"}
    await executor.checkpoint_store.save(
        "session", "prior", "collect_context", {"metadata": metadata, "status": "running"}
    )
    request = AgentRunRequest(messages=[], session_id="session", resume_from_run_id="prior", metadata=metadata)
    await executor._initial_state(request, "session", "first", "trace")
    with pytest.raises(ValueError, match="已被恢复"):
        await executor._initial_state(request, "session", "second", "trace")


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["external_action", "generation", "tool_success", "tool_failure"])
async def test_stream_preparation_respects_external_actions_and_tool_outcomes(executor, monkeypatch, mode):
    from app.models.schemas import TaskUnderstandingResult, ToolResult

    task = TaskUnderstandingResult(original_query="goal")
    if mode == "external_action":
        task.metadata = {"workflow": {"steps": [{"external_action": "backend.action"}]}}
    elif mode.startswith("tool"):
        task.metadata = {"capability_contract": {"required_tools": ["echo"]}}
        graph_state = {
            "status": "success",
            "stop_reason": "task_complete",
            "answer": "verified",
            "task_understanding": task,
            "tool_results": [
                ToolResult(tool_call_id="call", tool_name="echo", success=mode == "tool_success", output="evidence")
            ],
        }
        monkeypatch.setattr(executor, "_execute_required_tools", AsyncMock(return_value=graph_state))
    result = await executor._prepare_task_stream(
        AgentRunRequest(messages=[ChatMessage(role="user", content="goal")]), task, {}, "session", "run", "trace"
    )
    if mode == "generation":
        assert result["messages"] and result["short_answer"] is None
    elif mode == "external_action":
        assert result["short_answer"] == ""
        assert result["graph_state"]["tool_results"] == []
    elif mode == "tool_success":
        assert result["short_answer"] == "verified"
    else:
        assert result["status"] == "fail"
        assert result["stop_reason"] == "tool_execution_failed"


@pytest.mark.asyncio
@pytest.mark.parametrize("has_tools", [True, False])
async def test_empty_stream_uses_saved_answer_only_without_unsynthesized_tool_results(executor, monkeypatch, has_tools):
    from app.models.schemas import TaskUnderstandingResult, ToolResult

    async def stream(*args, **kwargs):
        yield ""

    client = SimpleNamespace(stream_chat=stream, chat=AsyncMock(return_value={"content": ""}))
    executor.default_llm_client = client
    state = {
        "status": "success",
        "stop_reason": "task_complete",
        "answer": "verified",
        "task_understanding": TaskUnderstandingResult(original_query="goal"),
    }
    if has_tools:
        state["tool_results"] = [ToolResult(tool_call_id="call", tool_name="echo", success=True, output="evidence")]
    executor.graph = SimpleNamespace(ainvoke=AsyncMock(return_value=state))
    monkeypatch.setattr(executor, "_initial_state", AsyncMock(return_value=state))
    events = [
        event async for event in executor.execute_stream(AgentRunRequest(messages=[], resume_from_run_id="prior"))
    ]
    if has_tools:
        assert events[-1]["event"] == "error"
        assert "未产出" in events[-1]["data"]["message"]
    else:
        assert events[-1]["event"] == "done"
        assert [event["data"]["text"] for event in events if event["event"] == "token"] == ["verified"]
