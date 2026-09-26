from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient

from app import api, judge
from app.memory_grader import grade_memory, percentile95


@pytest.mark.parametrize(
    ("path", "payload", "function", "arguments"),
    [
        ("trace", {"trace": []}, "grade_trace", ([],)),
        ("run", {"run": {"answer": "answer"}}, "grade_run", ({"answer": "answer"}, {})),
        ("capabilities", {"profile": {}}, "grade_capability_inventory", ({},)),
        ("latency", {"metrics": {}}, "grade_latency", ({}, {})),
    ],
)
def test_grading_endpoints_forward_payload_and_normalize_failures(monkeypatch, path, payload, function, arguments):
    grade = Mock(return_value={"passed": True, "score": 1})
    monkeypatch.setattr(api, function, grade)
    client = TestClient(api.app)
    assert client.post("/v1/eval/" + path, json=payload).json() == {
        "code": 200,
        "message": "success",
        "data": {"passed": True, "score": 1},
    }
    grade.assert_called_once_with(*arguments)
    grade.side_effect = ValueError("private input")
    response = client.post("/v1/eval/" + path, json=payload)
    assert response.json()["code"] == 500
    assert "private input" not in response.text


def test_health_reports_judge_availability(monkeypatch):
    monkeypatch.setattr(api, "judge_enabled", lambda: True)
    assert TestClient(api.app).get("/health").json()["data"] == {
        "status": "UP",
        "service": "agent-eval",
        "judge_enabled": True,
    }


@pytest.mark.parametrize(
    ("status", "body", "reason"),
    [
        (200, {}, "missing choices/message"),
        (200, {"choices": []}, "missing choices/message"),
        (200, {"choices": [{"message": {"content": "{broken}"}}]}, "not valid JSON"),
        (401, {}, "HTTP 401"),
        (503, {}, "HTTP 503"),
    ],
)
def test_judge_rejects_protocol_and_http_failures(monkeypatch, status, body, reason):
    monkeypatch.setenv("AGENT_EVAL_JUDGE_BASE_URL", "https://judge.invalid/v1")
    monkeypatch.setenv("AGENT_EVAL_JUDGE_MODEL", "test-model")
    monkeypatch.setenv("AGENT_EVAL_JUDGE_API_KEY", "synthetic")
    monkeypatch.setattr(judge.time, "sleep", lambda _: None)
    calls = []

    def post(url, **kwargs):
        calls.append(kwargs)
        return httpx.Response(status, json=body, request=httpx.Request("POST", url))

    monkeypatch.setattr(judge.httpx, "post", post)
    result = judge.judge_run({"answer": "test"})
    assert result["ok"] is False
    assert reason in result["reason"]
    assert len(calls) == (2 if status == 503 else 1)
    assert calls[0]["headers"]["Authorization"] == "Bearer synthetic"


def test_judge_malformed_transport_response_is_failure(monkeypatch):
    monkeypatch.setenv("AGENT_EVAL_JUDGE_BASE_URL", "https://judge.invalid/v1")
    monkeypatch.setenv("AGENT_EVAL_JUDGE_MODEL", "test-model")
    post = Mock(
        return_value=httpx.Response(200, text="not json", request=httpx.Request("POST", "https://judge.invalid"))
    )
    monkeypatch.setattr(judge.httpx, "post", post)
    assert judge.judge_run({})["ok"] is False
    post.assert_called_once()


@pytest.mark.parametrize("values", [[], [-1], [float("nan")], [float("inf")]])
def test_latency_samples_must_be_finite_nonnegative_and_present(values):
    with pytest.raises(ValueError, match="latency samples"):
        percentile95(values)


@pytest.mark.parametrize("missing", ["expected_ids", "isolation", "lifecycle"])
def test_memory_grading_requires_ground_truth_and_outcome_samples(missing):
    report = {
        "queries": [{"expected_ids": ["fact"], "found_ids": ["fact"], "elapsed_ms": 1} for _ in range(20)],
        "updates": [{"passed": True, "elapsed_ms": 1}],
        "isolation": [{"passed": True}],
        "lifecycle": [{"passed": True}],
    }
    if missing == "expected_ids":
        report["queries"][0][missing] = []
    else:
        report[missing] = []
    with pytest.raises(ValueError, match="expected facts|missing"):
        grade_memory(report, {})
