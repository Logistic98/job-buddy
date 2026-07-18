import pytest

from app.core.checkpoint.store import CheckpointStore
from app.core.observability.trace import TraceRecorder
from app.core.agent.executor import AgentExecutor
from app.models.schemas import AgentRunRequest, ChatMessage


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
async def test_executor_restores_latest_checkpoint_state(checkpoint_store):
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
            "messages": [{"role": "user", "content": "old"}],
            "objective": "old",
            "turn_count": 2,
            "tool_call_count": 1,
            "failure_count": 0,
            "tool_results": [],
            "permission_records": [],
            "observations": ["工具已执行"],
            "logs": [],
        },
    )

    executor = AgentExecutor(use_llm=False)
    executor.checkpoint_store = store
    request = AgentRunRequest(
        session_id=session_id,
        messages=[ChatMessage(role="user", content="continue")],
        metadata={"resume_from_checkpoint": True},
    )

    state = await executor._initial_state(request, session_id, "run_new", "trace_new")

    assert state["run_id"] == "run_new"
    assert state["trace_id"] == "trace_new"
    assert state["_resume_skip_until"] == "execute_tool"
    assert state["observations"] == ["工具已执行"]
    assert state["messages"][0].content == "continue"


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
    assert latest["state"]["error"] == "boom"


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
    await store.save(session_id, "run_a", "plan_created", {"turn": 1})
    await store.save(session_id, "run_b", "finalize", {"turn": 5})

    by_run = await store.load_latest_by_run(session_id, "run_a")
    assert by_run is not None
    assert by_run["run_id"] == "run_a"
    assert by_run["state"]["turn"] == 1

    snapshots = await store.list_snapshots(session_id)
    assert len(snapshots) == 2
    assert {s["run_id"] for s in snapshots} == {"run_a", "run_b"}
    assert all("state" not in s for s in snapshots)
    assert await store.load_latest_by_run(session_id, "run_missing") is None
