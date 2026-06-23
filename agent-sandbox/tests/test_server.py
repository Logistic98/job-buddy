
from __future__ import annotations

from fastapi.testclient import TestClient

from app.server.app import create_app


def test_health() -> None:
    client = TestClient(create_app())
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "message": "success", "data": {"status": "UP", "service": "agent-sandbox"}}


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
