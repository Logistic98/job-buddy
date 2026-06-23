import httpx
from fastapi.testclient import TestClient

from app.server import app
from app.tools.memory_search import run_memory_search
from app.tools.sandbox_execute import run_sandbox_execute
from app.tools.trace_summarize import run_trace_summarize

client = TestClient(app)


def test_trace_summarize_counts_events():
    events = [
        {"event": "run_start", "run_id": "run_1", "timestamp": "2026-01-01 00:00:00", "payload": {}},
        {"event": "finalize", "run_id": "run_1", "timestamp": "2026-01-01 00:00:05", "payload": {}},
        {"event": "run_end", "run_id": "run_1", "timestamp": "2026-01-01 00:00:06", "payload": {"error": "boom"}},
    ]
    result = run_trace_summarize({"events": events}, trace_id="t1")
    assert result.status == "success"
    assert result.data["total_events"] == 3
    assert result.data["event_counts"]["run_start"] == 1
    assert result.data["run_ids"] == ["run_1"]
    assert result.data["error_count"] == 1
    assert result.warnings


def test_trace_summarize_rejects_empty_events():
    result = run_trace_summarize({}, trace_id="t2")
    assert result.status == "error"
    assert result.error.code == "invalid_arguments"
    assert result.error.retryable is False


def test_memory_search_requires_query():
    result = run_memory_search({}, trace_id="t3")
    assert result.status == "error"
    assert result.error.code == "invalid_arguments"


def test_memory_search_handles_unavailable_service(monkeypatch):
    def raise_connect(*args, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "get", raise_connect)
    result = run_memory_search({"query": "java"}, trace_id="t4")
    assert result.status == "error"
    assert result.error.code == "memory_unavailable"
    assert result.error.retryable is True


def test_memory_search_success(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"code": 0, "data": [{"id": "mem_1", "content": "Java 偏好"}]}

    monkeypatch.setattr(httpx, "get", lambda *a, **kw: FakeResponse())
    result = run_memory_search({"query": "java"}, trace_id="t5")
    assert result.status == "success"
    assert len(result.data) == 1


def test_sandbox_execute_requires_command():
    result = run_sandbox_execute({}, trace_id="t6")
    assert result.status == "error"
    assert result.error.code == "invalid_arguments"


def test_sandbox_execute_success(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"ok": True, "returncode": 0, "stdout": "hello\n", "stderr": "", "args": []}

    monkeypatch.setattr(httpx, "post", lambda *a, **kw: FakeResponse())
    result = run_sandbox_execute({"command": "echo hello"}, trace_id="t7")
    assert result.status == "success"
    assert result.data["stdout"] == "hello\n"


def test_sandbox_execute_command_failure(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"ok": True, "returncode": 2, "stdout": "", "stderr": "not found", "args": []}

    monkeypatch.setattr(httpx, "post", lambda *a, **kw: FakeResponse())
    result = run_sandbox_execute({"command": "bad-cmd"}, trace_id="t8")
    assert result.status == "error"
    assert result.error.code == "command_failed"


def test_execute_endpoint_unknown_tool():
    response = client.post("/v1/tools/not_exists/execute", json={"arguments": {}})
    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 1
    assert body["data"]["error"]["code"] == "tool_not_found"


def test_sandbox_execute_requires_confirm():
    response = client.post("/v1/tools/sandbox_execute/execute", json={"arguments": {"command": "echo hi"}})
    body = response.json()
    assert body["code"] == 1
    assert body["data"]["status"] == "rejected"
    assert body["data"]["error"]["code"] == "confirmation_required"


def test_execute_endpoint_trace_summarize():
    response = client.post(
        "/v1/tools/core_trace_summarize/execute",
        json={"arguments": {"events": [{"event": "run_start", "run_id": "r1"}]}, "trace_id": "trace_x"},
    )
    body = response.json()
    assert body["code"] == 0
    assert body["data"]["status"] == "success"
    assert body["data"]["trace_id"] == "trace_x"


def test_registry_definitions_have_eight_elements():
    from app.registry import list_tools

    for tool in list_tools():
        for key in ("name", "description", "parameters", "returns", "errors", "permission", "example", "eval"):
            assert key in tool, f"{tool.get('name')} missing {key}"


def test_registry_and_executors_are_consistent():
    from app.server import validate_registry_consistency

    validate_registry_consistency()


def test_executor_exception_returns_structured_error(monkeypatch):
    import app.server as server_module

    def boom(arguments, trace_id=None):
        raise RuntimeError("boom")

    monkeypatch.setitem(server_module.TOOL_EXECUTORS, "core_trace_summarize", boom)
    response = client.post("/v1/tools/core_trace_summarize/execute", json={"arguments": {"events": []}})
    body = response.json()
    assert body["code"] == 1
    assert body["data"]["error"]["code"] == "tool_execution_error"
    assert body["data"]["error"]["retryable"] is True
