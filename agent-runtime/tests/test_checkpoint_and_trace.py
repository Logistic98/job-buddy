import pytest
from fastapi import HTTPException

from app.core.agent.executor import AgentExecutor
from app.core.checkpoint.store import CheckpointStore
from app.core.common.constants import RuntimeStatus, StopReason
from app.core.observability.trace import TraceRecorder
from app.models.schemas import AgentPlan, AgentPlanStep, AgentRunRequest, ChatMessage, TaskUnderstandingResult


@pytest.mark.asyncio
async def test_checkpoint_save_and_load_latest(checkpoint_store):
    store = checkpoint_store
    session_id = "session_unit_test"
    run_id = "run_unit_test"

    await store.save(session_id, run_id, "plan_created", {"turn": 1, "plan": {"objective": "sample-task"}})
    await store.save(session_id, run_id, "tool_execute_end", {"turn": 2, "result": "ok"})

    latest = await store.load_latest(session_id)
    assert latest is not None
    assert latest["session_id"] == session_id
    assert latest["stage"] == "tool_execute_end"
    assert latest["state"]["turn"] == 2


@pytest.mark.asyncio
async def test_postgres_checkpoint_save_combines_insert_and_retention_cleanup(monkeypatch):
    from app.core.common.settings import settings

    calls = []

    class Connection:
        async def execute(self, sql, *args):
            calls.append((sql, args))

    class Acquire:
        async def __aenter__(self):
            return Connection()

        async def __aexit__(self, exc_type, exc, traceback):
            return False

    class Pool:
        def acquire(self):
            return Acquire()

    store = CheckpointStore(database_url="postgresql://runtime.invalid/job_buddy")

    async def fake_pool():
        return Pool()

    monkeypatch.setattr(store, "_get_pool", fake_pool)
    monkeypatch.setattr(settings.config.checkpoint, "enabled", True)
    monkeypatch.setattr(settings.config.checkpoint, "max_per_session", 5)

    await store.save("session_one_roundtrip", "run_one_roundtrip", "observe", {"turn": 1})

    assert len(calls) == 1
    assert "WITH inserted AS" in calls[0][0]
    assert "DELETE FROM agent_run_checkpoint" in calls[0][0]
    assert calls[0][1][-1] == 4


@pytest.mark.asyncio
async def test_checkpoint_redacts_nested_credentials(checkpoint_store):
    store = checkpoint_store
    await store.save(
        "session_secret",
        "run_secret",
        "understand_goal",
        {
            "metadata": {
                "llm_service": {"api_key": "sk-live-secret", "base_url": "https://example.com"},
                "database_url": "postgresql://user:password@db.example/app",
            },
            "error": "authorization=Bearer secret-token",
        },
    )
    latest = await store.load_latest("session_secret")
    rendered = str(latest)
    assert "sk-live-secret" not in rendered
    assert "secret-token" not in rendered
    assert "user:password" not in rendered
    assert "[REDACTED]" in rendered


@pytest.mark.asyncio
async def test_checkpoint_summarizes_sandbox_code_and_output(checkpoint_store):
    source_marker = "CHECKPOINT_CODE_MARKER_71"
    output_marker = "CHECKPOINT_OUTPUT_MARKER_92"
    await checkpoint_store.save(
        "session_code",
        "run_code",
        "observe",
        {
            "plan": {
                "tool_calls": [
                    {
                        "name": "sandbox_code_execute",
                        "arguments": {
                            "language": "python",
                            "code": f"print('{source_marker}')",
                        },
                    }
                ]
            },
            "tool_results": [
                {
                    "tool_name": "sandbox_code_execute",
                    "success": True,
                    "output": {
                        "sandboxed": True,
                        "exit_code": 0,
                        "stdout": output_marker,
                        "stderr": "",
                    },
                    "metadata": {
                        "execution_detail": {
                            "code": f"print('{source_marker}')",
                            "code_chars": len(source_marker) + 9,
                        }
                    },
                }
            ],
            "observations": [f"工具 sandbox_code_execute 执行成功：{{'stdout': '{output_marker}'}}"],
        },
    )

    latest = await checkpoint_store.load_latest("session_code")
    rendered = str(latest)

    assert source_marker not in rendered
    assert output_marker not in rendered
    assert "sha256" in rendered
    assert "[SANDBOX_OUTPUT_REDACTED]" in rendered


def test_checkpoint_does_not_inherit_memory_database_url(monkeypatch):
    monkeypatch.delenv("AGENT_RUNTIME_DATABASE_URL", raising=False)
    monkeypatch.setenv("AGENT_MEMORY_DATABASE_URL", "postgresql://memory:secret@db/memory")
    store = CheckpointStore()
    assert store._database_url == ""


def test_checkpoint_warns_once_when_enabled_without_runtime_dsn(monkeypatch):
    from loguru import logger

    from app.core.checkpoint import store as checkpoint_module
    from app.core.common.settings import settings

    monkeypatch.setattr(settings.config.checkpoint, "enabled", True)
    monkeypatch.setattr(checkpoint_module, "_missing_dsn_warning_emitted", False)
    messages = []
    sink_id = logger.add(lambda message: messages.append(str(message)), level="WARNING")
    try:
        CheckpointStore(database_url="")
        CheckpointStore(database_url="")
    finally:
        logger.remove(sink_id)

    warnings = [item for item in messages if "Checkpoint 已开启但未配置" in item]
    assert len(warnings) == 1


@pytest.mark.asyncio
async def test_checkpoint_handles_pydantic_models(checkpoint_store):
    from app.models.schemas import AgentPlan

    store = checkpoint_store
    plan = AgentPlan(objective="sample-task", final_answer="done", is_complete=True)
    await store.save("session_p", "run_p", "finalize", {"plan": plan})
    latest = await store.load_latest("session_p")
    assert latest["state"]["plan"]["objective"] == "sample-task"
    assert latest["state"]["plan"]["is_complete"] is True


@pytest.mark.asyncio
async def test_request_llm_overrides_do_not_mutate_shared_executor():
    executor = AgentExecutor(use_llm=False)
    first = AgentRunRequest(
        messages=[ChatMessage(role="user", content="one")],
        metadata={"llm_service": {"api_key": "key-one", "base_url": "https://one.example/v1", "model": "one"}},
    )
    second = AgentRunRequest(
        messages=[ChatMessage(role="user", content="two")],
        metadata={"llm_service": {"api_key": "key-two", "base_url": "https://two.example/v1", "model": "two"}},
    )
    first_client = executor._resolve_request_llm(first)
    second_client = executor._resolve_request_llm(second)
    first_graph = executor._build_graph(first_client)
    second_graph = executor._build_graph(second_client)
    assert first_client is not second_client
    assert first_graph is not second_graph
    assert executor.llm_client is None
    assert executor.task_understanding.llm_client is None
    assert executor.planner.llm_client is None
    await first_client.aclose()
    await second_client.aclose()


@pytest.mark.asyncio
async def test_executor_restores_requested_checkpoint_run_instead_of_newer_session_run(checkpoint_store):
    store = checkpoint_store
    session_id = "session_resume_test"
    await store.save(
        session_id,
        "run_old",
        "execute_tool",
        {
            "run_id": "run_old",
            "trace_id": "trace_old",
            "session_id": session_id,
            "metadata": {"tenant_id": "tenant-a", "user_id": "user-a", "turn_id": "turn-old"},
            "messages": [{"role": "user", "content": "old"}],
            "objective": "old",
            "turn_count": 2,
            "tool_call_count": 1,
            "failure_count": 0,
            "tool_results": [],
            "permission_records": [],
            "observations": ["工具已执行"],
            "selected_tool_calls": [{"id": "call_old", "name": "echo", "arguments": {"text": "old"}}],
            "logs": [],
        },
    )
    await store.save(
        session_id,
        "run_newer",
        "collect_context",
        {
            "run_id": "run_newer",
            "trace_id": "trace_newer",
            "session_id": session_id,
            "objective": "newer unrelated run",
            "observations": ["不能恢复这条"],
        },
    )

    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = store
    request = AgentRunRequest(
        session_id=session_id,
        messages=[ChatMessage(role="user", content="old")],
        resume_from_run_id="run_old",
        metadata={"tenant_id": "tenant-a", "user_id": "user-a", "turn_id": "turn-old"},
    )

    state = await executor._initial_state(request, session_id, "run_new", "trace_new")

    assert state["run_id"] == "run_new"
    assert state["trace_id"] == "trace_new"
    assert state["_resume_skip_until"] == "execute_tool"
    assert state["observations"] == ["工具已执行"]
    assert state["messages"][0].content == "old"
    assert state["selected_tool_calls"][0].id == "call_old"
    assert state["_resumed_from_run_id"] == "run_old"


@pytest.mark.asyncio
async def test_executor_resumes_invalid_plan_terminal_from_safe_replan_cursor(checkpoint_store):
    session_id = "session_invalid_plan_resume"
    source_run_id = "run_invalid_plan_source"
    request = AgentRunRequest(
        session_id=session_id,
        messages=[ChatMessage(role="user", content="输出 Mermaid、LaTeX 和 Python 示例")],
        resume_from_run_id=source_run_id,
        metadata={
            "tenant_id": "tenant-a",
            "user_id": "user-a",
            "turn_id": "turn-invalid-plan",
        },
    )
    task = TaskUnderstandingResult(original_query="输出 Mermaid、LaTeX 和 Python 示例")
    invalid_plan = AgentPlan(
        objective="生成三类示例",
        steps=[AgentPlanStep(id="step_2", goal="整理结果", depends_on=["sandbox_code_execute"])],
    )
    await checkpoint_store.save(
        session_id,
        source_run_id,
        "finalize",
        {
            "run_id": source_run_id,
            "trace_id": "trace_invalid_plan_source",
            "session_id": session_id,
            "messages": request.messages,
            "metadata": request.metadata,
            "task_understanding": task,
            "candidate_tools": [],
            "plan": invalid_plan,
            "selected_tool_calls": [],
            "selected_tool_call": None,
            "observations": ["上一轮工具执行失败，重新规划时必须保留该证据"],
            "reflection": {"decision": "finalize"},
            "status": RuntimeStatus.FAIL.value,
            "stop_reason": StopReason.INVALID_PLAN_DEPENDENCY.value,
            "answer": "计划依赖校验失败。",
            "should_stop": True,
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store

    state = await executor._initial_state(request, session_id, "run_invalid_plan_resumed", "trace_resumed")

    assert state["_resume_skip_until"] == "tool_search"
    assert state["_resumed_from_stage"] == "tool_search"
    assert state["plan"] is None
    assert state["selected_tool_calls"] == []
    assert state["selected_tool_call"] is None
    assert state["reflection"] == {}
    assert state["observations"][0] == "上一轮工具执行失败，重新规划时必须保留该证据"
    assert "计划依赖校验失败" in state["observations"][-1]
    assert state["status"] == RuntimeStatus.RUNNING.value
    assert state["should_stop"] is False


@pytest.mark.asyncio
async def test_checkpoint_resume_claim_is_single_use(checkpoint_store):
    first = await checkpoint_store.claim_resume("session_claim", "run_source", "run_resume_1")
    second = await checkpoint_store.claim_resume("session_claim", "run_source", "run_resume_2")

    assert first is True
    assert second is False


@pytest.mark.asyncio
async def test_executor_rejects_checkpoint_owner_mismatch(checkpoint_store):
    await checkpoint_store.save(
        "session_owner",
        "run_owner",
        "collect_context",
        {
            "run_id": "run_owner",
            "trace_id": "trace_owner",
            "session_id": "session_owner",
            "status": "running",
            "metadata": {
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-terminal",
            },
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store
    response = await executor.execute(
        AgentRunRequest(
            session_id="session_owner",
            messages=[ChatMessage(role="user", content="继续")],
            resume_from_run_id="run_owner",
            metadata={"tenant_id": "tenant-b", "user_id": "user-b"},
        )
    )

    assert response.status.value == "fail"
    assert "归属" in str(response.error)


@pytest.mark.asyncio
async def test_executor_rejects_terminal_checkpoint_resume(checkpoint_store):
    await checkpoint_store.save(
        "session_terminal",
        "run_terminal",
        "finalize",
        {
            "run_id": "run_terminal",
            "trace_id": "trace_terminal",
            "session_id": "session_terminal",
            "status": "success",
            "stop_reason": "task_complete",
            "metadata": {
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-terminal",
            },
            "messages": [{"role": "user", "content": "继续"}],
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store
    response = await executor.execute(
        AgentRunRequest(
            session_id="session_terminal",
            messages=[ChatMessage(role="user", content="继续")],
            resume_from_run_id="run_terminal",
            metadata={
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-terminal",
            },
        )
    )

    assert response.status.value == "fail"
    assert "终态" in str(response.error)


@pytest.mark.asyncio
async def test_executor_rejects_ownerless_checkpoint_resume(checkpoint_store):
    await checkpoint_store.save(
        "session_ownerless",
        "run_ownerless",
        "collect_context",
        {
            "run_id": "run_ownerless",
            "session_id": "session_ownerless",
            "messages": [{"role": "user", "content": "继续"}],
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store

    response = await executor.execute(
        AgentRunRequest(
            session_id="session_ownerless",
            messages=[ChatMessage(role="user", content="继续")],
            resume_from_run_id="run_ownerless",
        )
    )

    assert response.status.value == "fail"
    assert "归属" in str(response.error)


@pytest.mark.asyncio
async def test_executor_rejects_resume_message_mismatch(checkpoint_store):
    await checkpoint_store.save(
        "session_message_mismatch",
        "run_message_mismatch",
        "collect_context",
        {
            "run_id": "run_message_mismatch",
            "session_id": "session_message_mismatch",
            "messages": [{"role": "user", "content": "原始任务"}],
            "metadata": {
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-message-mismatch",
            },
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store

    response = await executor.execute(
        AgentRunRequest(
            session_id="session_message_mismatch",
            messages=[ChatMessage(role="user", content="篡改后的任务")],
            resume_from_run_id="run_message_mismatch",
            metadata={
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-message-mismatch",
            },
        )
    )

    assert response.status.value == "fail"
    assert "用户消息" in str(response.error)


@pytest.mark.asyncio
async def test_executor_rejects_resume_turn_mismatch_even_when_content_matches(checkpoint_store):
    await checkpoint_store.save(
        "session_turn_mismatch",
        "run_turn_mismatch",
        "collect_context",
        {
            "run_id": "run_turn_mismatch",
            "session_id": "session_turn_mismatch",
            "messages": [{"role": "user", "content": "相同任务"}],
            "metadata": {
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-source",
            },
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store

    response = await executor.execute(
        AgentRunRequest(
            session_id="session_turn_mismatch",
            messages=[ChatMessage(role="user", content="相同任务")],
            resume_from_run_id="run_turn_mismatch",
            metadata={
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "turn_id": "turn-other",
            },
        )
    )

    assert response.status.value == "fail"
    assert "turnId" in str(response.error)


@pytest.mark.asyncio
async def test_checkpoint_removes_attachment_content_but_keeps_reference(checkpoint_store):
    await checkpoint_store.save(
        "session_attachment",
        "run_attachment",
        "collect_context",
        {
            "messages": [{"role": "user", "content": "总结附件"}],
            "metadata": {
                "tenant_id": "tenant-a",
                "user_id": "user-a",
                "attachments": [
                    {
                        "attachmentId": "attachment-1",
                        "fileName": "resume.pdf",
                        "content": "PRIVATE_ATTACHMENT_BODY",
                        "untrusted": True,
                        "injectionHits": ["ignore previous"],
                    }
                ],
            },
        },
    )

    latest = await checkpoint_store.load_latest_by_run("session_attachment", "run_attachment", "tenant-a", "user-a")
    rendered = str(latest)

    assert "PRIVATE_ATTACHMENT_BODY" not in rendered
    assert "ignore previous" not in rendered
    assert latest["state"]["metadata"]["attachments"] == [{"attachmentId": "attachment-1", "fileName": "resume.pdf"}]


@pytest.mark.asyncio
async def test_executor_saves_runtime_error_checkpoint(checkpoint_store):

    class FailingGraph:
        async def ainvoke(self, state):
            raise RuntimeError("boom")

    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store
    executor.graph = FailingGraph()
    request = AgentRunRequest(session_id="session_error_test", messages=[ChatMessage(role="user", content="fail")])

    response = await executor.execute(request)
    latest = await executor.checkpoint_store.load_latest("session_error_test")

    assert response.status.value == "fail"
    assert latest is not None
    assert latest["stage"] == "runtime_error"
    assert latest["state"]["error"] == "RuntimeError: boom"


@pytest.mark.asyncio
async def test_understanding_only_failure_does_not_restore_previous_run_directive(checkpoint_store):
    class FailingGraph:
        async def ainvoke(self, state):
            raise RuntimeError("current understanding failed")

    session_id = "session_understanding_failure"
    await checkpoint_store.save(
        session_id,
        "run_old",
        "finalize",
        {
            "run_id": "run_old",
            "trace_id": "trace_old",
            "session_id": session_id,
            "directive": {"intent": "job.recommend", "next_action": "call_get_recommend_jobs"},
            "task_understanding": {"router": "llm", "intent": {"intent": "job.recommend"}},
        },
    )
    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = checkpoint_store
    executor.graph = FailingGraph()
    request = AgentRunRequest(
        session_id=session_id,
        messages=[ChatMessage(role="user", content="分析当前简历与目标岗位的匹配度")],
        metadata={"understanding_only": True},
    )

    response = await executor.execute(request)

    assert response.status.value == "fail"
    assert response.directive is None
    assert response.task_understanding is None
    latest = await checkpoint_store.load_latest(session_id)
    assert latest["stage"] == "runtime_error"
    assert latest["run_id"] != "run_old"
    assert "directive" not in latest["state"]


@pytest.mark.asyncio
async def test_trace_recorder_filters_by_run(tmp_path):
    recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))
    await recorder.record("trace_a", "run_start", {"k": 1}, run_id="run_1")
    await recorder.record("trace_a", "plan_created", {"k": 2}, run_id="run_1")
    await recorder.record("trace_b", "run_start", {"k": 3}, run_id="run_2")

    events_run_1 = recorder.list_by_run("run_1")
    assert len(events_run_1) == 2
    assert all(e.run_id == "run_1" for e in events_run_1)
    assert {e.event for e in events_run_1} == {"run_start", "plan_created"}


@pytest.mark.asyncio
async def test_trace_recorder_redacts_sensitive_payload_and_error(tmp_path):
    recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))
    await recorder.record(
        "trace_secret",
        "tool_end",
        {"headers": {"Authorization": "Bearer secret-token"}, "api_key": "sk-secret"},
        run_id="run_secret",
        error="password=hunter2",
    )
    event = recorder.list_by_run("run_secret")[0]
    rendered = str(event.model_dump())
    assert "secret-token" not in rendered
    assert "sk-secret" not in rendered
    assert "hunter2" not in rendered
    assert "[REDACTED]" in rendered


@pytest.mark.asyncio
async def test_trace_recorder_persists_and_replays_after_restart(tmp_path):
    persist_dir = str(tmp_path / "traces")
    recorder = TraceRecorder(persist_dir=persist_dir)
    await recorder.record("trace_a", "run_start", {"k": 1}, run_id="run_replay")
    await recorder.record("trace_a", "run_end", {"k": 2}, run_id="run_replay")

    jsonl = (tmp_path / "traces" / "run_replay.jsonl").read_text(encoding="utf-8")
    assert jsonl.count("\n") == 2

    restarted = TraceRecorder(persist_dir=persist_dir)
    replayed = restarted.list_by_run("run_replay")
    assert [e.event for e in replayed] == ["run_start", "run_end"]
    assert replayed[0].payload == {"k": 1}


@pytest.mark.asyncio
async def test_trace_recorder_memory_window_falls_back_to_disk(tmp_path, monkeypatch):
    from app.core.common.settings import settings as runtime_settings

    monkeypatch.setattr(runtime_settings.config.observability, "max_events", 1)
    recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))
    await recorder.record("trace_a", "run_start", run_id="run_window")
    await recorder.record("trace_a", "plan_created", run_id="run_window")
    await recorder.record("trace_b", "run_start", run_id="run_other")

    # run_window 的事件已被内存窗口滚动清理，必须能从落盘文件回放。
    events = recorder.list_by_run("run_window")
    assert [e.event for e in events] == ["run_start", "plan_created"]


@pytest.mark.asyncio
async def test_checkpoint_load_by_run_and_list_snapshots(checkpoint_store):
    store = checkpoint_store
    session_id = "session_multi_run"
    owner = {"tenant_id": "tenant-a", "user_id": "user-a"}
    await store.save(session_id, "run_a", "plan_created", {"turn": 1, "metadata": owner})
    await store.save(session_id, "run_b", "finalize", {"turn": 5, "metadata": owner})

    by_run = await store.load_latest_by_run(session_id, "run_a", "tenant-a", "user-a")
    assert by_run is not None
    assert by_run["run_id"] == "run_a"
    assert by_run["state"]["turn"] == 1

    snapshots = await store.list_snapshots(session_id, "tenant-a", "user-a")
    assert len(snapshots) == 2
    assert {s["run_id"] for s in snapshots} == {"run_a", "run_b"}
    assert all("state" not in s for s in snapshots)
    assert await store.load_latest_by_run(session_id, "run_missing", "tenant-a", "user-a") is None


@pytest.mark.asyncio
async def test_checkpoint_api_requires_owner_and_never_returns_state(monkeypatch, checkpoint_store):
    from app.api import runtime as runtime_api

    await checkpoint_store.save(
        "session_api",
        "run_api",
        "collect_context",
        {
            "metadata": {"tenant_id": "tenant-a", "user_id": "user-a"},
            "private": "must-not-leak",
        },
    )
    executor = type("Executor", (), {"checkpoint_store": checkpoint_store})()
    monkeypatch.setattr(runtime_api, "_executor", executor)

    with pytest.raises(HTTPException) as error:
        await runtime_api.list_checkpoints(
            "session_api",
            run_id="run_api",
            x_tenant_id=None,
            x_operator_id=None,
        )

    assert error.value.status_code == 400
    response = await runtime_api.list_checkpoints(
        "session_api",
        run_id="run_api",
        x_tenant_id="tenant-a",
        x_operator_id="user-a",
    )
    assert response["data"]["stage"] == "collect_context"
    assert "state" not in response["data"]
    assert "must-not-leak" not in str(response)

    snapshots = await runtime_api.list_checkpoints(
        "session_api",
        run_id=None,
        x_tenant_id="tenant-a",
        x_operator_id="user-a",
    )
    assert [item["run_id"] for item in snapshots["data"]] == ["run_api"]
