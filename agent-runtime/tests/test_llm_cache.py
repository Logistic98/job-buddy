
import pytest
import httpx

from app.core.llm.openai_client import OpenAICompatibleClient
from app.models.schemas import ChatMessage


@pytest.mark.asyncio
async def test_openai_client_request_cache(monkeypatch):
    calls = {"count": 0}

    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {"prompt_tokens": 1}}

    class FakeClient:
        def __init__(self, timeout=None):
            self.timeout = timeout

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def post(self, *args, **kwargs):
            calls["count"] += 1
            return FakeResponse()

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)
    client = OpenAICompatibleClient(base_url="http://llm.test/v1", api_key="test", model="m")

    first = await client.chat([ChatMessage(role="user", content="hi")])
    second = await client.chat([ChatMessage(role="user", content="hi")])

    assert first["content"] == "ok"
    assert second["cache"]["hit"] is True
    assert calls["count"] == 1
    assert client.get_cache_metrics()["hits"] == 1


def test_anthropic_tools_cache_control_only_on_last_tool():
    from app.models.schemas import ToolDefinition

    client = OpenAICompatibleClient(provider="claude_max", api_key="t", model="m", prompt_cache_enabled=True)
    tools = [
        ToolDefinition(name=f"tool_{i}", description="d", input_schema={"type": "object", "properties": {}})
        for i in range(6)
    ]
    payload = client._build_payload([ChatMessage(role="user", content="hi")], tools, None, None, stream=False)

    marked = [item for item in payload["tools"] if "cache_control" in item]
    assert len(marked) == 1
    assert payload["tools"][-1]["cache_control"] == {"type": "ephemeral"}


def test_disable_thinking_payload_only_for_deepseek_routing_calls():
    client = OpenAICompatibleClient(base_url="http://llm.test/v1", api_key="t", model="m", provider="deepseek_api")
    messages = [ChatMessage(role="user", content="hi")]

    routing = client._build_payload(messages, None, None, None, stream=False, disable_thinking=True)
    assert routing["thinking"] == {"type": "disabled"}

    synthesis = client._build_payload(messages, None, None, None, stream=True)
    assert "thinking" not in synthesis

    client.understanding_thinking_disabled = False
    disabled = client._build_payload(messages, None, None, None, stream=False, disable_thinking=True)
    assert "thinking" not in disabled


def test_disable_thinking_payload_ignored_for_non_deepseek_provider():
    client = OpenAICompatibleClient(provider="chatgpt_pro", api_key="t", model="m")
    payload = client._build_payload([ChatMessage(role="user", content="hi")], None, None, None, stream=False, disable_thinking=True)
    assert "thinking" not in payload


@pytest.mark.asyncio
async def test_fetch_latest_model_picks_newest_by_created(monkeypatch):
    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"data": [
                {"id": "model-old", "created": 100},
                {"id": "model-new", "created": 300},
                {"id": "model-mid", "created": 200},
            ]}

    class FakeClient:
        def __init__(self, timeout=None):
            self.timeout = timeout

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def get(self, *args, **kwargs):
            return FakeResponse()

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)
    client = OpenAICompatibleClient(base_url="http://llm.test", api_key="t", model="m")
    latest = await client._fetch_latest_model()
    assert latest == "model-new"
