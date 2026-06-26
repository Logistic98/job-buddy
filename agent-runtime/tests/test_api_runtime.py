
import pytest

try:
    from fastapi.testclient import TestClient
except ImportError:
    TestClient = None

from app.server import create_app
from app.core.common.settings import settings


def assert_success_envelope(response):
    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 200
    assert body["message"] == "success"
    return body


@pytest.fixture
def client(monkeypatch):
    if TestClient is None:
        pytest.skip("fastapi.testclient 不可用")
    # API 层测试必须自给自足，不能依赖本机 .env 里的真实 LLM 密钥：
    # 注入确定性的本地配置，让 config 脱敏和 echo 语义回退在 CI（无密钥）下稳定复现。
    monkeypatch.setattr(settings.config.llm_service, "api_key", "sk-test-runtime-key-1234")
    monkeypatch.setattr(settings.config.runtime, "use_llm_planner", False)
    monkeypatch.setattr("app.api.runtime._executor", None)
    return TestClient(create_app())


def test_health_endpoint(client):
    response = client.get("/health")
    body = assert_success_envelope(response)
    assert body["data"]["status"] == "healthy"


def test_list_tools_endpoint(client):
    response = client.get("/v1/runtime/tools")
    data = assert_success_envelope(response)["data"]
    names = {item["name"] for item in data}
    assert "echo" in names
    assert "file_read" in names
    assert "resume_parse" in names
    assert "resume_analyze" in names
    assert "resume_match" in names


def test_config_endpoint_masks_api_key(client):
    response = client.get("/v1/runtime/config")
    config = assert_success_envelope(response)["data"]["config"]
    api_key = config["llm_service"]["api_key"]
    assert "****" in api_key


def test_run_agent_endpoint_echo(client):
    payload = {"messages": [{"role": "user", "content": "请回显 hello api"}]}
    response = client.post("/v1/runtime/runs", json=payload)
    body = assert_success_envelope(response)["data"]
    assert body["status"] in {"success", "paused"}
    assert body["run_id"].startswith("run_")
    assert any(r["tool_name"] == "echo" for r in body["tool_results"])


def test_agent_runs_endpoint_uses_standard_envelope(client):
    payload = {"messages": [{"role": "user", "content": "请回显 hello agent api"}]}
    response = client.post("/v1/agent/runs", json=payload)
    body = assert_success_envelope(response)["data"]
    assert body["status"] in {"success", "paused"}
    assert body["run_id"].startswith("run_")


def test_invoke_tool_direct_echo(client):
    response = client.post(
        "/v1/runtime/tools/echo/invoke",
        json={"arguments": {"text": "direct invoke"}},
    )
    data = assert_success_envelope(response)["data"]
    assert data["success"] is True
    assert data["output"]["text"] == "direct invoke"
    assert data["tool_name"] == "echo"


def test_direct_tool_invoke_ignores_caller_workspace(client, tmp_path, monkeypatch):
    safe_workspace = tmp_path / "safe"
    attacker_workspace = tmp_path / "attacker"
    safe_workspace.mkdir()
    attacker_workspace.mkdir()
    (attacker_workspace / "secret.txt").write_text("caller-controlled-secret", encoding="utf-8")
    monkeypatch.setattr(settings.config.runtime, "workspace_dir", str(safe_workspace))

    response = client.post(
        "/v1/runtime/tools/file_read/invoke",
        json={
            "arguments": {"path": "secret.txt"},
            "workspace_dir": str(attacker_workspace),
        },
    )

    data = assert_success_envelope(response)["data"]
    assert data["success"] is False
    assert "caller-controlled-secret" not in response.text


def test_direct_tool_invoke_blocks_destructive_tools(client, tmp_path, monkeypatch):
    safe_workspace = tmp_path / "safe"
    safe_workspace.mkdir()
    monkeypatch.setattr(settings.config.runtime, "workspace_dir", str(safe_workspace))

    response = client.post(
        "/v1/runtime/tools/file_write/invoke",
        json={"arguments": {"path": "out.txt", "content": "blocked"}},
    )

    assert response.status_code == 403
    assert not (safe_workspace / "out.txt").exists()


def test_reload_builtin_tools_endpoint(client):
    response = client.post("/v1/runtime/tools/reload-builtins")
    data = assert_success_envelope(response)["data"]
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
    events = assert_success_envelope(response)["data"]
    assert events
    assert all(item["run_id"] == run_id for item in events)
