from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient

from app import server
from app.models import ToolError, ToolResult
from app.tools.memory_search import run_memory_search
from app.tools.sandbox_execute import run_sandbox_execute


def test_registry_rejects_missing_executor(monkeypatch):
    monkeypatch.setattr(server, "TOOL_EXECUTORS", {})
    with pytest.raises(RuntimeError, match="不一致"):
        server.validate_registry_consistency()


def test_catalog_and_health_are_available():
    client = TestClient(server.app)
    assert client.get("/health").json()["data"]["status"] == "UP"
    assert client.get("/v1/tools").json()["data"]


def test_registered_tool_without_executor_is_server_error(monkeypatch):
    monkeypatch.delitem(server.TOOL_EXECUTORS, "memory_search")
    response = TestClient(server.app).post("/v1/tools/memory_search/execute", json={"arguments": {"query": "q"}})
    assert response.json()["code"] == 500
    assert response.json()["data"]["error"]["code"] == "executor_not_found"


def test_memory_owner_headers_override_payload(monkeypatch):
    execute = Mock(return_value=ToolResult(status="success", summary="done"))
    monkeypatch.setitem(server.TOOL_EXECUTORS, "memory_search", execute)
    response = TestClient(server.app).post(
        "/v1/tools/memory_search/execute",
        headers={"X-Tenant-Id": "tenant", "X-Operator-Id": "owner"},
        json={"arguments": {"query": "q", "tenant_id": "forged", "operator_id": "forged"}},
    )
    assert response.json()["code"] == 200
    assert execute.call_args.args[0] == {"query": "q", "tenant_id": "tenant", "operator_id": "owner"}


def test_boss_missing_payload_is_created_with_trusted_owner(monkeypatch):
    execute = Mock(return_value=ToolResult(status="success", summary="done"))
    monkeypatch.setitem(server.TOOL_EXECUTORS, "boss_browser", execute)
    response = TestClient(server.app).post(
        "/v1/tools/boss_browser/execute",
        headers={"X-Tenant-Id": "tenant", "X-Operator-Id": "owner"},
        json={"arguments": {"operation": "status", "payload": "invalid"}},
    )
    assert response.json()["code"] == 200
    assert execute.call_args.args[0]["payload"] == {"_trusted_owner_key": "tenant\x00owner"}


@pytest.mark.parametrize(
    ("status", "code", "expected"),
    [
        ("rejected", "any", 403),
        ("error", "invalid_arguments", 400),
        ("error", "tool_not_found", 404),
        ("error", "confirmation_required", 403),
    ],
)
def test_tool_error_status_mapping(status, code, expected):
    result = ToolResult(status=status, summary="failure", error=ToolError(code=code, message="failure"))
    assert server._response_code(result) == expected


def test_memory_scope_authentication_and_timeout_retry(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "synthetic")
    request = Mock(side_effect=httpx.ReadTimeout("timeout"))
    monkeypatch.setattr(httpx, "get", request)
    result = run_memory_search({"query": "q", "scope": "session"})
    assert result.error.code == "memory_timeout"
    assert request.call_count == 2
    assert request.call_args.kwargs["params"]["scope"] == "session"
    assert request.call_args.kwargs["headers"]["X-Internal-Service-Token"] == "synthetic"


@pytest.mark.parametrize(
    ("error", "code"),
    [(httpx.ReadTimeout("timeout"), "sandbox_timeout"), (httpx.ConnectError("offline"), "sandbox_unavailable")],
)
def test_sandbox_timeout_and_transport_failure_preserve_auth_and_timeout(monkeypatch, error, code):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "synthetic")
    request = Mock(side_effect=error)
    monkeypatch.setattr(httpx, "post", request)
    result = run_sandbox_execute({"command": "command", "timeout": 2})
    assert result.error.code == code
    assert result.error.retryable is True
    request.assert_called_once()
    assert request.call_args.kwargs["json"]["options"] == {"timeout": 2, "check": False}
    assert request.call_args.kwargs["headers"]["X-Internal-Service-Token"] == "synthetic"
