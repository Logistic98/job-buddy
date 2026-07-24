from __future__ import annotations

from fastapi.testclient import TestClient

from app.server.app import _bounded_output, _effective_config, _safe_cwd, create_app
from app.server.schemas import SandboxPolicySchema


def test_health() -> None:
    client = TestClient(create_app())
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"code": 200, "message": "success", "data": {"status": "UP", "service": "agent-sandbox"}}


def test_python_code_endpoint(fake_srt) -> None:
    client = TestClient(create_app())
    resp = client.post(
        "/v1/python/code",
        json={
            "code": "print('hello service')",
            "options": {"check": True},
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["stdout"].strip() == "hello service"


def test_command_requires_exactly_one_command_shape() -> None:
    client = TestClient(create_app())
    resp = client.post("/v1/commands", json={"argv": ["echo", "a"], "command": "echo b"})
    assert resp.status_code == 400


def test_http_policy_cannot_weaken_workspace_or_network(tmp_path) -> None:
    policy = SandboxPolicySchema(
        network={
            "allowedDomains": ["example.com"],
            "allowLocalBinding": True,
            "allowAllUnixSockets": True,
        },
        filesystem={
            "allowRead": ["/"],
            "allowWrite": ["/"],
            "denyRead": ["/etc/shadow"],
            "denyWrite": [],
        },
        ignoreViolations={"filesystem": ["/"]},
        enableWeakerNestedSandbox=True,
        enableWeakerNetworkIsolation=True,
    )

    config = _effective_config(policy, tmp_path)

    workspace = str(tmp_path.resolve())
    assert config.network.allowedDomains == []
    assert config.network.allowLocalBinding is False
    assert config.network.allowAllUnixSockets is False
    assert config.filesystem.allowRead == [workspace]
    assert config.filesystem.allowWrite == [workspace]
    assert "/etc/shadow" in config.filesystem.denyRead
    assert config.ignoreViolations == {}
    assert config.enableWeakerNestedSandbox is False
    assert config.enableWeakerNetworkIsolation is False


def test_explicit_empty_write_policy_remains_read_only(tmp_path) -> None:
    policy = SandboxPolicySchema(
        filesystem={
            "allowRead": [str(tmp_path)],
            "allowWrite": [],
        }
    )

    config = _effective_config(policy, tmp_path)

    assert config.filesystem.allowRead == [str(tmp_path.resolve())]
    assert config.filesystem.allowWrite == []


def test_configured_workspace_is_an_allowed_cwd(tmp_path, monkeypatch) -> None:
    workspace = tmp_path / "workspace"
    monkeypatch.setenv("AGENT_SANDBOX_WORKSPACE_DIR", str(workspace))

    resolved, cleanup = _safe_cwd(workspace)
    try:
        assert resolved == workspace.resolve()
    finally:
        cleanup()


def test_request_cannot_inject_process_environment(fake_srt) -> None:
    client = TestClient(create_app())
    resp = client.post(
        "/v1/python/code",
        json={
            "code": "import os; print(os.environ.get('NODE_OPTIONS', 'missing'))",
            "options": {"env": {"NODE_OPTIONS": "--require=/tmp/attack.js", "LANG": "C.UTF-8"}},
        },
    )
    assert resp.status_code == 200
    assert resp.json()["stdout"].strip() == "missing"


def test_code_size_limit_rejected_before_execution() -> None:
    client = TestClient(create_app())
    resp = client.post("/v1/python/code", json={"code": "x" * 1048577})
    assert resp.status_code == 422


def test_output_is_bounded(monkeypatch) -> None:
    monkeypatch.setattr("app.server.app._MAX_OUTPUT_CHARS", 1024)
    output = _bounded_output("x" * 2048)
    assert len(output) < 1100
    assert output.endswith("[OUTPUT_TRUNCATED]")


def test_server_does_not_inherit_host_environment(fake_srt, monkeypatch) -> None:
    monkeypatch.setenv("JOB_BUDDY_HOST_SECRET", "should-not-leak")
    client = TestClient(create_app())

    resp = client.post(
        "/v1/python/code",
        json={
            "code": "import os; print(os.environ.get('JOB_BUDDY_HOST_SECRET', 'missing'))",
            "options": {"check": True},
        },
    )

    assert resp.status_code == 200
    assert resp.json()["stdout"].strip() == "missing"
