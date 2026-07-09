import pytest

try:
    from fastapi.testclient import TestClient
except ImportError:
    TestClient = None

from fastapi import FastAPI

from app.internal_auth import INTERNAL_AUTH_HEADER, install_internal_auth

pytestmark = pytest.mark.skipif(TestClient is None, reason="fastapi testclient not available")


def _build_app() -> FastAPI:
    app = FastAPI()

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/v1/protected")
    async def protected():
        return {"code": 200, "message": "success", "data": {}}

    return app


def test_auth_disabled_when_token_not_configured(monkeypatch):
    monkeypatch.delenv("AGENT_INTERNAL_SERVICE_TOKEN", raising=False)
    app = _build_app()
    install_internal_auth(app)
    client = TestClient(app)
    assert client.get("/v1/protected").status_code == 200


def test_missing_token_rejected_with_unified_envelope(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "secret-token")
    app = _build_app()
    install_internal_auth(app)
    client = TestClient(app)
    response = client.get("/v1/protected")
    assert response.status_code == 401
    body = response.json()
    assert body["code"] == 401
    assert body["message"]


def test_wrong_token_rejected(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "secret-token")
    app = _build_app()
    install_internal_auth(app)
    client = TestClient(app)
    response = client.get("/v1/protected", headers={INTERNAL_AUTH_HEADER: "wrong"})
    assert response.status_code == 401


def test_valid_token_accepted(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "secret-token")
    app = _build_app()
    install_internal_auth(app)
    client = TestClient(app)
    response = client.get("/v1/protected", headers={INTERNAL_AUTH_HEADER: "secret-token"})
    assert response.status_code == 200


def test_health_exempt_from_auth(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "secret-token")
    app = _build_app()
    install_internal_auth(app)
    client = TestClient(app)
    assert client.get("/health").status_code == 200
