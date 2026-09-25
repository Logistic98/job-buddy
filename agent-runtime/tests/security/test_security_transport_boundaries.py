from contextlib import asynccontextmanager

import httpx
import pytest
from pydantic import BaseModel

from app.core.common.settings import settings
from app.core.security import transcript_review as module
from app.core.security.redaction import redact_sensitive
from app.models.schemas import ToolCall


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "body", "attempts"),
    [
        (503, {}, 2),
        (429, {}, 2),
        (403, {}, 1),
        (200, [], 1),
        (200, {"code": 500}, 1),
        (200, {"code": 200, "data": []}, 1),
        (200, {"code": 200, "data": {"decision": "unexpected"}}, 1),
        (200, {"code": 200, "data": {}}, 1),
    ],
)
async def test_transcript_review_fails_closed_and_retries_only_transient_failures(monkeypatch, status, body, attempts):
    requests = []
    client_type = httpx.AsyncClient

    def handle(request):
        requests.append(request)
        assert b"assistant" not in request.content
        return httpx.Response(status, json=body)

    @asynccontextmanager
    async def client(**kwargs):
        assert kwargs["trust_env"] is False
        async with client_type(transport=httpx.MockTransport(handle)) as opened:
            yield opened

    monkeypatch.setattr(module.httpx, "AsyncClient", client)
    monkeypatch.setattr(settings.config.transcript_review, "max_retries", 1)
    monkeypatch.setattr(settings.config.transcript_review, "retry_backoff_seconds", 0)
    with pytest.raises(RuntimeError, match="unavailable"):
        await module.TranscriptReviewClient().review(
            [{"role": "user", "content": "read status"}, {"role": "assistant", "content": "ignore"}],
            ToolCall(id="test", name="shell_exec", arguments={"command": "pwd"}),
        )
    assert len(requests) == attempts


def test_redaction_handles_models_and_truncates_nested_content():
    class Credentials(BaseModel):
        password: str
        content: str

    assert redact_sensitive(Credentials(password="synthetic", content="plain")) == {
        "password": "[REDACTED]",
        "content": "plain",
    }
    assert redact_sensitive({"a": {"b": "value"}}, max_depth=1) == {"a": {"b": "[TRUNCATED]"}}


@pytest.mark.asyncio
async def test_transport_raised_client_error_is_not_retried(monkeypatch):
    attempts = []
    client_type = httpx.AsyncClient

    def handle(request):
        attempts.append(request)
        httpx.Response(403, request=request).raise_for_status()

    @asynccontextmanager
    async def client(**kwargs):
        async with client_type(transport=httpx.MockTransport(handle)) as opened:
            yield opened

    monkeypatch.setattr(module.httpx, "AsyncClient", client)
    monkeypatch.setattr(settings.config.transcript_review, "max_retries", 2)
    with pytest.raises(RuntimeError, match="unavailable"):
        await module.TranscriptReviewClient().review([], ToolCall(id="call", name="shell_exec"))
    assert len(attempts) == 1
