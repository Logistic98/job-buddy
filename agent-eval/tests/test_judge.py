import httpx
from fastapi.testclient import TestClient

from app import judge as judge_module
from app.judge import _parse_verdict, judge_enabled, judge_run
from app.api import app


def _clear_judge_env(monkeypatch):
    for key in [
        "AGENT_EVAL_JUDGE_BASE_URL",
        "AGENT_EVAL_JUDGE_API_KEY",
        "AGENT_EVAL_JUDGE_MODEL",
        "AGENT_EVAL_JUDGE_TIMEOUT_SECONDS",
    ]:
        monkeypatch.delenv(key, raising=False)


def test_judge_disabled_when_not_configured(monkeypatch):
    _clear_judge_env(monkeypatch)
    assert judge_enabled() is False
    result = judge_run({"answer": "hello"})
    assert result["enabled"] is False
    assert "not configured" in result["reason"]


def test_judge_endpoint_returns_code_1_when_unavailable(monkeypatch):
    _clear_judge_env(monkeypatch)
    client = TestClient(app)
    response = client.post("/v1/eval/judge", json={"run": {"answer": "hi"}})
    body = response.json()
    assert response.status_code == 200
    assert body["code"] == 1
    assert body["data"]["enabled"] is False


def test_judge_run_parses_model_verdict(monkeypatch):
    _clear_judge_env(monkeypatch)
    monkeypatch.setenv("AGENT_EVAL_JUDGE_BASE_URL", "http://judge.local/v1")
    monkeypatch.setenv("AGENT_EVAL_JUDGE_MODEL", "judge-model")

    def fake_post(url, **kwargs):
        assert url == "http://judge.local/v1/chat/completions"
        payload = {
            "choices": [
                {"message": {"content": '{"score": 0.85, "verdict": "pass", "reasons": ["回答切题"]}'}}
            ]
        }
        request = httpx.Request("POST", url)
        return httpx.Response(200, json=payload, request=request)

    monkeypatch.setattr(judge_module.httpx, "post", fake_post)
    result = judge_run({"answer": "Java volatile 保证可见性"}, {"intent": "technical_qa"})
    assert result["enabled"] is True
    assert result["ok"] is True
    assert result["score"] == 0.85
    assert result["verdict"] == "pass"
    assert result["reasons"] == ["回答切题"]


def test_judge_run_retries_then_reports_timeout(monkeypatch):
    _clear_judge_env(monkeypatch)
    monkeypatch.setenv("AGENT_EVAL_JUDGE_BASE_URL", "http://judge.local/v1")
    monkeypatch.setenv("AGENT_EVAL_JUDGE_MODEL", "judge-model")
    calls = {"count": 0}

    def fake_post(url, **kwargs):
        calls["count"] += 1
        raise httpx.ConnectTimeout("timeout")

    monkeypatch.setattr(judge_module.httpx, "post", fake_post)
    result = judge_run({"answer": "hi"})
    assert calls["count"] == 2
    assert result["enabled"] is True
    assert result["ok"] is False
    assert "failed after 2 attempts" in result["reason"]


def test_parse_verdict_rejects_invalid_payloads():
    assert _parse_verdict("not json at all") is None
    assert _parse_verdict('{"score": "abc"}') is None
    parsed = _parse_verdict('前置说明 {"score": 1.2, "reasons": ["ok"]} 后缀')
    assert parsed["score"] == 1.0
    assert parsed["verdict"] == "pass"
