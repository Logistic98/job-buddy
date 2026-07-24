import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.internal_auth import install_internal_auth


def build_app():
    app = FastAPI()
    install_internal_auth(app)

    @app.get("/health")
    def health():
        return {"ok": True}

    @app.get("/private")
    def private():
        return {"ok": True}

    return app


def test_internal_token_protects_non_health_routes(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "test-secret")
    monkeypatch.setenv("JOB_BUDDY_ENVIRONMENT", "development")
    client = TestClient(build_app())
    assert client.get("/health").status_code == 200
    assert client.get("/private").status_code == 401
    assert client.get("/private", headers={"X-Internal-Service-Token": "wrong"}).status_code == 401
    assert client.get("/private", headers={"X-Internal-Service-Token": "test-secret"}).status_code == 200


def test_production_requires_internal_token(monkeypatch):
    monkeypatch.delenv("AGENT_INTERNAL_SERVICE_TOKEN", raising=False)
    monkeypatch.setenv("JOB_BUDDY_ENVIRONMENT", "production")
    monkeypatch.setenv("HOST", "127.0.0.1")
    with pytest.raises(RuntimeError, match="AGENT_INTERNAL_SERVICE_TOKEN"):
        build_app()


def test_non_loopback_bind_requires_internal_token_even_in_development(monkeypatch):
    monkeypatch.delenv("AGENT_INTERNAL_SERVICE_TOKEN", raising=False)
    monkeypatch.setenv("JOB_BUDDY_ENVIRONMENT", "development")
    monkeypatch.setenv("HOST", "0.0.0.0")

    with pytest.raises(RuntimeError, match="AGENT_INTERNAL_SERVICE_TOKEN"):
        build_app()


def test_loopback_bind_can_run_without_internal_token_in_development(monkeypatch):
    monkeypatch.delenv("AGENT_INTERNAL_SERVICE_TOKEN", raising=False)
    monkeypatch.setenv("JOB_BUDDY_ENVIRONMENT", "development")
    monkeypatch.setenv("HOST", "::1")

    client = TestClient(build_app())

    assert client.get("/health").status_code == 200
    assert client.get("/private").status_code == 200
