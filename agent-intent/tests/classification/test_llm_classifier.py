"""LLM 兜底的超时、重试和不可信响应契约。"""

import json
from unittest.mock import Mock

import httpx
import pytest

from app import llm_classifier


@pytest.fixture
def client(monkeypatch):
    for key, value in {
        "ENABLED": "true",
        "BASE_URL": "https://model.invalid/v1/",
        "API_KEY": "synthetic",
        "MODEL": "test-model",
        "TIMEOUT_SECONDS": "2",
        "MAX_RETRIES": "1",
    }.items():
        monkeypatch.setenv(f"AGENT_INTENT_LLM_{key}", value)
    post = Mock()
    monkeypatch.setattr(llm_classifier.httpx, "post", post)
    monkeypatch.setattr(llm_classifier.time, "sleep", Mock())
    return post


def response(status=200, content=None):
    return httpx.Response(
        status,
        request=httpx.Request("POST", "https://model.invalid/v1/chat/completions"),
        json={
            "choices": [
                {"message": {"content": content or json.dumps({"domain": "job", "risk": "low", "confidence": 0.9})}}
            ]
        },
    )


@pytest.mark.parametrize("failure", [httpx.ReadTimeout("timeout"), httpx.ConnectError("offline"), response(503)])
def test_transient_failure_retries_once_then_returns_valid_result(client, failure):
    client.side_effect = [failure, response()]
    result = llm_classifier.classify_with_llm("query")
    assert result.router == "llm"
    assert result.confidence == 0.9
    assert client.call_count == 2
    assert client.call_args.kwargs["timeout"] == 2
    assert client.call_args.kwargs["json"]["messages"][-1] == {"role": "user", "content": "query"}
    llm_classifier.time.sleep.assert_called_once_with(0.2)


@pytest.mark.parametrize(
    ("failure", "attempts"),
    [(httpx.ReadTimeout("timeout"), 2), (response(503), 2), (response(401), 1), (response(400), 1)],
)
def test_exhausted_or_permanent_failure_degrades_without_unbounded_retry(client, failure, attempts):
    if isinstance(failure, Exception):
        client.side_effect = failure
    else:
        client.return_value = failure
    assert llm_classifier.classify_with_llm("query") is None
    assert client.call_count == attempts


@pytest.mark.parametrize("content", ["not-json", "[]", "null", '{"domain":"job","risk":"low","confidence":"invalid"}'])
def test_malformed_model_output_degrades_without_retry(client, content):
    client.return_value = response(content=content)
    assert llm_classifier.classify_with_llm("query") is None
    client.assert_called_once()


@pytest.mark.parametrize(("confidence", "expected"), [(-2, 0), (2, 1)])
def test_json_fences_and_confidence_bounds(client, confidence, expected):
    client.return_value = response(
        content="```json\n" + json.dumps({"domain": "job", "risk": "low", "confidence": confidence}) + "\n```"
    )
    assert llm_classifier.classify_with_llm("query").confidence == expected
