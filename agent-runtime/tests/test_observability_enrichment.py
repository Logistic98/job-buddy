import pytest
from httpx import ASGITransport, AsyncClient

from app.core.agent.executor import AgentExecutor
from app.core.agent.graph import AgentGraphBuilder
from app.core.common.constants import TraceEventName
from app.core.llm.usage import record_usage, start_usage_tracking
from app.core.observability.trace import TraceRecorder
from app.core.tool.gateway import ToolGatewayResult
from app.models.schemas import ToolCall, ToolResult


class _StubGateway:
    def __init__(self, results):
        self._results = list(results)

    async def execute(
        self,
        call,
        mode,
        context,
        task_understanding=None,
        transcript_messages=None,
    ):
        return self._results.pop(0)


class _StubCheckpointStore:
    async def save(self, *args, **kwargs):
        return None


def _graph_builder(tmp_path, gateway):
    builder = AgentGraphBuilder.__new__(AgentGraphBuilder)
    builder.tool_gateway = gateway
    builder.trace_recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))
    builder.checkpoint_store = _StubCheckpointStore()
    return builder


def _state(calls):
    return {
        "run_id": "run_obs",
        "trace_id": "trace_obs",
        "session_id": "session_obs",
        "permission_mode": "default",
        "metadata": {},
        "selected_tool_calls": calls,
        "tool_results": [],
        "tool_call_count": 0,
        "failure_count": 0,
    }


@pytest.mark.asyncio
async def test_tool_execute_end_carries_duration_and_per_tool_results(tmp_path):
    gateway = _StubGateway(
        [
            ToolGatewayResult(result=ToolResult(tool_call_id="c1", tool_name="sample_tool", success=True, output="ok")),
        ]
    )
    builder = _graph_builder(tmp_path, gateway)
    state = _state([ToolCall(id="c1", name="sample_tool", arguments={})])

    await builder._execute_tool(state)

    events = builder.trace_recorder.list_by_run("run_obs")
    end_events = [e for e in events if e.event == TraceEventName.TOOL_EXECUTE_END.value]
    assert len(end_events) == 1
    payload = end_events[0].payload
    assert payload["success"] is True
    assert "duration_ms" in payload
    assert payload["results"][0]["tool"] == "sample_tool"
    assert payload["results"][0]["success"] is True
    assert isinstance(payload["results"][0]["duration_ms"], int)


@pytest.mark.asyncio
async def test_tool_failure_emits_tool_execute_failed_event(tmp_path):
    gateway = _StubGateway(
        [
            ToolGatewayResult(
                result=ToolResult(tool_call_id="c1", tool_name="broken_tool", success=False, error="上游超时")
            ),
        ]
    )
    builder = _graph_builder(tmp_path, gateway)
    state = _state([ToolCall(id="c1", name="broken_tool", arguments={})])

    await builder._execute_tool(state)

    events = builder.trace_recorder.list_by_run("run_obs")
    failed = [e for e in events if e.event == TraceEventName.TOOL_EXECUTE_FAILED.value]
    assert len(failed) == 1
    assert failed[0].payload["tool"] == "broken_tool"
    assert failed[0].payload["error"] == "上游超时"
    assert failed[0].payload["permission_denied"] is False
    assert state["failure_count"] == 1


@pytest.mark.asyncio
async def test_executor_records_llm_usage_event_when_llm_called(tmp_path):
    executor = AgentExecutor(use_llm=False)
    executor.trace_recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))

    class _Client:
        def get_cache_metrics(self):
            return {"hits": 1, "misses": 2, "stores": 2}

    start_usage_tracking()
    record_usage({"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150})
    await executor._record_llm_usage("trace_llm", "run_llm", _Client())

    events = executor.trace_recorder.list_by_run("run_llm")
    assert [e.event for e in events] == [TraceEventName.LLM_USAGE.value]
    payload = events[0].payload
    assert payload["llm_calls"] == 1
    assert payload["total_tokens"] == 150
    assert payload["llm_cache"] == {"hits": 1, "misses": 2, "stores": 2}


@pytest.mark.asyncio
async def test_executor_skips_llm_usage_event_without_calls(tmp_path):
    executor = AgentExecutor(use_llm=False)
    executor.trace_recorder = TraceRecorder(persist_dir=str(tmp_path / "traces"))

    start_usage_tracking()
    await executor._record_llm_usage("trace_llm", "run_no_llm")

    assert executor.trace_recorder.list_by_run("run_no_llm") == []


@pytest.mark.asyncio
async def test_request_logging_middleware_sets_request_id_header():
    from fastapi import FastAPI

    from app.server import request_logging_middleware

    app = FastAPI()
    app.middleware("http")(request_logging_middleware)

    @app.get("/ping")
    async def ping():
        return {"ok": True}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        generated = await client.get("/ping")
        assert generated.status_code == 200
        assert generated.headers["X-Request-Id"].startswith("req_")

        propagated = await client.get("/ping", headers={"X-Request-Id": "req_upstream_1"})
        assert propagated.headers["X-Request-Id"] == "req_upstream_1"
