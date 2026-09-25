import subprocess
from unittest.mock import Mock

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.core.exceptions import SandboxCommandNotFoundError, SandboxProcessError
from app.core.models import SandboxResult
from app.internal_auth import _is_loopback_host
from app.server import app as server
from app.server.schemas import CodeFileRequest, ExecutionOptionsSchema


@pytest.mark.parametrize(
    ("path", "payload", "method"),
    [
        ("commands", {"argv": ["command"]}, "command"),
        ("commands", {"command": "command"}, "command_string"),
        ("cli", {"executable": "command"}, "cli"),
        ("shell", {"command": "command"}, "shell"),
        ("code-file", {"code": "print(1)"}, "code_file"),
    ],
)
def test_execution_routes_dispatch_and_cleanup(monkeypatch, path, payload, method):
    client = Mock()
    getattr(client, method).return_value = SandboxResult(["command"], 0, "done", "")
    cleanup = Mock()
    monkeypatch.setattr(
        server, "_prepare_execution", lambda *args: server.PreparedExecution(client, ExecutionOptionsSchema(), cleanup)
    )
    response = TestClient(server.create_app()).post("/v1/" + path, json=payload)
    assert response.status_code == 200
    assert response.json()["stdout"] == "done"
    getattr(client, method).assert_called_once()
    cleanup.assert_called_once()


def test_command_requires_exactly_one_input():
    client = TestClient(server.create_app())
    assert client.post("/v1/commands", json={}).status_code == 400
    assert client.post("/v1/commands", json={"argv": ["cmd"], "command": "cmd"}).status_code == 400


@pytest.mark.parametrize(
    ("error", "status"),
    [
        (SandboxProcessError("failed", returncode=2, stdout="output", stderr="error"), 422),
        (SandboxCommandNotFoundError("missing"), 500),
        (subprocess.TimeoutExpired("secret command", 1), 504),
        (RuntimeError("secret details"), 500),
    ],
)
def test_execution_errors_have_classified_status_without_secret_details(error, status):
    with pytest.raises(HTTPException) as failure:
        server._execute("test", Mock(side_effect=error))
    assert failure.value.status_code == status
    assert "secret" not in str(failure.value.detail)
    assert failure.value.detail["request_id"]


def test_busy_executor_does_not_prepare_or_release_unacquired_slot(monkeypatch):
    slots = Mock()
    slots.acquire.return_value = False
    monkeypatch.setattr(server, "_EXECUTION_SLOTS", slots)
    prepare = Mock()
    monkeypatch.setattr(server, "_prepare_execution", prepare)
    with pytest.raises(HTTPException) as failure:
        server._run_with_request("test", None, None, Mock())
    assert failure.value.status_code == 429
    prepare.assert_not_called()
    slots.release.assert_not_called()


@pytest.mark.parametrize("failure", [True, False])
def test_readiness_rejects_failed_or_unexpected_execution(monkeypatch, failure):
    client = Mock()
    if failure:
        client.command.side_effect = SandboxProcessError("failed", returncode=2, stderr="namespace denied")
    else:
        client.command.return_value = SandboxResult([], 0, "unexpected", "")
    monkeypatch.setattr(server, "SandboxClient", Mock(return_value=client))
    with pytest.raises(RuntimeError, match="namespace denied|unexpected output"):
        server._probe_sandbox_runtime()
    workspace = server.SandboxClient.call_args.kwargs["cwd"]
    assert not workspace.exists()


def test_readiness_reuses_result_published_while_waiting_for_lock(monkeypatch):
    probe = Mock()
    cached = server._CachedReadinessProbe(probe, 30)

    class PublishingLock:
        def __enter__(self):
            cached._expires_at = float("inf")
            cached._healthy, cached._detail = True, "ready"

        def __exit__(self, *args):
            pass

    cached._lock = PublishingLock()
    assert cached.check() == (True, "ready")
    probe.assert_not_called()


@pytest.mark.parametrize("home", ["/", "/nonexistent-test-java"])
def test_java_read_paths_require_real_runtime_root(monkeypatch, home):
    monkeypatch.setenv("JAVA_HOME", home)
    assert server._trusted_runtime_read_paths() == []


def test_working_directory_requires_configured_workspace(monkeypatch, tmp_path):
    monkeypatch.delenv("AGENT_SANDBOX_WORKSPACE_DIR", raising=False)
    assert server._is_allowed_cwd(tmp_path) is False
    monkeypatch.setenv("AGENT_SANDBOX_WORKSPACE_DIR", str(tmp_path / "allowed"))
    assert server._is_allowed_cwd(tmp_path) is False
    assert server._narrow_allowed_paths([str(tmp_path)], ["subdir"], tmp_path) == [str(tmp_path / "subdir")]


@pytest.mark.parametrize("value", [[], {"code": "code", "suffix": ".js", "dependencies": ["pkg"]}])
def test_code_schema_rejects_wrong_shape_and_non_python_dependencies(value):
    with pytest.raises(ValueError):
        CodeFileRequest.model_validate(value)


@pytest.mark.parametrize(("host", "expected"), [("localhost", True), ("invalid-host", False)])
def test_loopback_host_validation(host, expected):
    assert _is_loopback_host(host) is expected
