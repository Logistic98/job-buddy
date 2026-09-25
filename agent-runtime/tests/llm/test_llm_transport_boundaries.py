import asyncio
from unittest.mock import AsyncMock, Mock

import httpx
import pytest

from app.core.llm.openai_client import LLMServiceError, OpenAICompatibleClient
from app.models.schemas import ChatMessage, ToolDefinition


@pytest.fixture
def client():
    return OpenAICompatibleClient(
        base_url="https://model.invalid/v1",
        api_key="synthetic",
        model="test",
        request_cache_enabled=False,
        max_retries=1,
        retry_backoff_seconds=0,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["timeout", "client_error", "server_error", "malformed"])
async def test_chat_failures_retry_only_transient_errors(client, monkeypatch, mode):
    calls = []

    def handle(request):
        calls.append(request)
        if mode == "timeout":
            raise httpx.ReadTimeout("timeout", request=request)
        if mode == "malformed":
            return httpx.Response(200, json={})
        return httpx.Response(401 if mode == "client_error" else 503)

    client._http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
    with pytest.raises(LLMServiceError):
        await client.chat([ChatMessage(role="user", content="test")])
    assert len(calls) == (2 if mode in {"timeout", "server_error"} else 1)
    await client.aclose()


@pytest.mark.asyncio
async def test_stream_skips_nondata_invalid_and_empty_frames(client):
    frames = [
        "event: message",
        "",
        "data:",
        "data: {bad",
        'data: {"choices": []}',
        'data: {"choices":[{"delta":{"reasoning_content":"reason"}}]}',
        'data: {"choices":[{"delta":{"content":"answer"}}]}',
        "data: [DONE]",
        'data: {"choices":[{"delta":{"content":"ignored"}}]}',
    ]
    client._http = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, text="\n".join(frames)))
    )
    assert [piece async for piece in client.stream_chat([ChatMessage(role="user", content="test")])] == [
        {"type": "reasoning", "text": "reason"},
        {"type": "answer", "text": "answer"},
    ]
    await client.aclose()


@pytest.mark.asyncio
async def test_stream_http_failure_is_classified(client):
    client._http = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(503)))
    with pytest.raises(LLMServiceError, match="流式调用失败"):
        _ = [piece async for piece in client.stream_chat([])]
    await client.aclose()


@pytest.mark.asyncio
async def test_close_cancels_inflight_and_releases_http(client):
    event = asyncio.Event()
    pending = asyncio.create_task(event.wait())
    client._inflight["key"] = pending
    http = AsyncMock()
    client._http = http
    await client.aclose()
    assert pending.cancelled()
    assert client._inflight == {}
    assert client._http is None
    http.aclose.assert_awaited_once()
    client._consume_task_exception(pending)


@pytest.mark.parametrize(
    ("provider", "base", "suffix", "fallback"),
    [
        ("claude", "https://model.invalid", "/v1/messages", "claude-sonnet-4-6"),
        ("anthropic", "https://model.invalid/v1", "/messages", "claude-sonnet-4-6"),
        ("claude_max", "https://model.invalid/v1/messages", "", "claude-sonnet-4-6"),
        ("openai", "https://model.invalid/v1/chat/completions", "", "gpt-4o"),
    ],
)
def test_provider_endpoints_are_not_duplicated(provider, base, suffix, fallback):
    client = OpenAICompatibleClient(provider=provider, base_url=base, api_key="synthetic", model="test")
    assert client.chat_completions_url == base + suffix
    assert client._fallback_model() == fallback


def test_anthropic_payload_response_and_stream_mapping():
    client = OpenAICompatibleClient(provider="claude", api_key="synthetic", model="test", prompt_cache_enabled=True)
    payload = client._build_payload(
        [ChatMessage(role="system", content="instructions"), ChatMessage(role="user", content="question")],
        [ToolDefinition(name="tool", description="test")],
        0,
        10,
        True,
    )
    assert payload["system"][0]["text"] == "instructions"
    assert payload["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert client._headers()["x-api-key"] == "synthetic"
    assert client._parse_response({"content": [{"type": "text", "text": "answer"}]})["content"] == "answer"
    assert client._parse_stream_event(
        {"type": "content_block_delta", "delta": {"type": "thinking_delta", "thinking": "reason"}}
    ) == ("reasoning", "reason")
    assert client._parse_stream_event(
        {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "answer"}}
    ) == ("answer", "answer")
    assert client._parse_stream_event({"type": "message_stop"}) == (None, None)


def test_openai_tool_and_named_message_fields(client):
    payload = client._build_payload(
        [ChatMessage(role="tool", content="result", name="tool", tool_call_id="call")],
        [ToolDefinition(name="tool", description="test")],
        0,
        10,
        False,
    )
    assert payload["tools"][0]["function"]["name"] == "tool"
    assert payload["messages"][0]["name"] == "tool"
    assert payload["messages"][0]["tool_call_id"] == "call"
    assert payload["tool_choice"] == "auto"
    client._store_cache("key", {"answer": "uncached"})
    assert client._cache == {}
    assert client._default_base_url("unknown") == ""


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("models", "expected"),
    [
        ([], None),
        ([{"name": "first"}, {"name": "second"}], "first"),
        ([{"id": "old", "created_at": "2020"}, {"id": "new", "created_at": "2026"}], "new"),
    ],
)
async def test_model_discovery_supports_empty_and_timestamped_catalogs(client, models, expected):
    client.base_url = "https://model.invalid"
    client._http = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"models": models}))
    )
    assert await client._fetch_latest_model() == expected
    await client.aclose()


@pytest.mark.asyncio
async def test_model_discovery_failure_uses_provider_fallback(client):
    client.model = ""
    client._http = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(503)))
    await client._ensure_model()
    assert client.model == "deepseek-chat"
    await client.aclose()


def test_unavailable_trace_scope_falls_back_to_unscoped(client, monkeypatch):
    from app.core.observability import trace

    monkeypatch.setattr(trace, "current_trace_context", Mock(side_effect=RuntimeError("unavailable")))
    assert client._cache_scope() == "unscoped"


@pytest.mark.parametrize(
    ("identity", "expected"),
    [({"tenant_id": "tenant", "user_id": "user"}, "tenant:user"), ({"user_id": "user"}, "-:user")],
)
def test_cache_is_scoped_to_owner_and_evicts_oldest_entry(client, monkeypatch, identity, expected):
    monkeypatch.setattr("app.core.observability.trace.current_trace_context", lambda: identity)
    assert client._cache_scope() == expected
    client.request_cache_enabled = True
    client.request_cache_max_entries = 1
    first = {"content": "first"}
    client._store_cache("first", first)
    first["content"] = "changed"
    assert client._cache["first"]["content"] == "first"
    client._store_cache("second", {"content": "second"})
    assert list(client._cache) == ["second"]
    assert client.get_cache_metrics()["stores"] == 2
