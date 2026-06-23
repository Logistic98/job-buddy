
import pytest

try:
    from fastapi.testclient import TestClient
except ImportError:
    TestClient = None

from app.server import create_app


@pytest.fixture
def client():
    if TestClient is None:
        pytest.skip("fastapi.testclient 不可用")
    return TestClient(create_app())


def test_health_endpoint(client):
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["data"]["status"] == "healthy"


def test_list_tools_endpoint(client):
    response = client.get("/v1/runtime/tools")
    assert response.status_code == 200
    data = response.json()["data"]
    names = {item["name"] for item in data}
    assert "echo" in names
    assert "file_read" in names
    assert "resume_parse" in names
    assert "resume_analyze" in names
    assert "resume_match" in names


def test_config_endpoint_masks_api_key(client):
    response = client.get("/v1/runtime/config")
    assert response.status_code == 200
    config = response.json()["data"]["config"]
    api_key = config["llm_service"]["api_key"]
    assert "****" in api_key


def test_run_agent_endpoint_echo(client):
    payload = {"messages": [{"role": "user", "content": "请回显 hello api"}]}
    response = client.post("/v1/runtime/runs", json=payload)
    assert response.status_code == 200
    body = response.json()["data"]
    assert body["status"] in {"success", "paused"}
    assert body["run_id"].startswith("run_")
    assert any(r["tool_name"] == "echo" for r in body["tool_results"])


def test_invoke_tool_direct_echo(client):
    response = client.post(
        "/v1/runtime/tools/echo/invoke",
        json={"arguments": {"text": "direct invoke"}},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["success"] is True
    assert data["output"]["text"] == "direct invoke"
    assert data["tool_name"] == "echo"


def test_reload_builtin_tools_endpoint(client):
    response = client.post("/v1/runtime/tools/reload-builtins")
    assert response.status_code == 200
    data = response.json()["data"]
    assert "resume_analyze" in data["tools"]


def test_invoke_tool_unknown_returns_404(client):
    response = client.post(
        "/v1/runtime/tools/no_such_tool/invoke",
        json={"arguments": {}},
    )
    assert response.status_code == 404


def test_trace_events_endpoint_query_by_run_id(client):
    payload = {"messages": [{"role": "user", "content": "请回显 trace 测试"}]}
    run_response = client.post("/v1/runtime/runs", json=payload).json()["data"]
    run_id = run_response["run_id"]

    response = client.get(f"/v1/runtime/trace-events?run_id={run_id}")
    assert response.status_code == 200
    events = response.json()["data"]
    assert events
    assert all(item["run_id"] == run_id for item in events)
