import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.api import agent, runtime
from app.core.common.constants import PermissionMode
from app.core.common.settings import settings
from app.models.schemas import AgentRunRequest, ChatMessage


@pytest.mark.asyncio
async def test_stream_api_serializes_unicode_events_and_terminal_sentinel(monkeypatch):
    async def stream(request):
        assert request.messages[0].content == "hello"
        yield {"event": "token", "data": {"text": "中文"}}
        yield {"data": {"status": "done"}}

    monkeypatch.setattr(agent, "get_executor", lambda: SimpleNamespace(execute_stream=stream))
    response = await agent.run_agent_stream(AgentRunRequest(messages=[ChatMessage(role="user", content="hello")]))
    frames = [chunk async for chunk in response.body_iterator]
    assert frames[0] == 'event: token\ndata: {"text": "中文"}\n\n'
    assert frames[1].startswith("event: message\n")
    assert frames[-1] == "data: [DONE]\n\n"
    assert response.headers["X-Accel-Buffering"] == "no"


@pytest.mark.asyncio
async def test_reload_endpoints_register_tools_and_rebuild_executor(monkeypatch):
    registry = Mock()
    registry.names.return_value = ["echo"]
    executor = SimpleNamespace(registry=registry)
    constructor = Mock(return_value=executor)
    reload_settings = Mock()
    monkeypatch.setattr(runtime, "AgentExecutor", constructor)
    monkeypatch.setattr(runtime, "_executor", None)
    monkeypatch.setattr(runtime, "reload_settings", reload_settings)
    mcp = AsyncMock(return_value=["echo"])
    builtins = Mock(return_value=["echo"])
    monkeypatch.setattr(runtime, "register_mcp_tools", mcp)
    monkeypatch.setattr(runtime, "register_missing_builtin_tools", builtins)
    assert runtime.get_executor() is executor
    assert (await runtime.reload_mcp_tools())["data"]["registered"] == ["echo"]
    assert (await runtime.reload_builtin_tools())["data"]["tools"] == ["echo"]
    result = await runtime.reload_runtime_config("config/test.yaml")
    reload_settings.assert_called_once_with("config/test.yaml")
    assert constructor.call_count == 2
    assert "config" in result["data"]


@pytest.mark.asyncio
async def test_checkpoint_list_forwards_identity_without_state(monkeypatch):
    store = Mock(list_snapshots=AsyncMock(return_value=[{"run_id": "run", "stage": "done"}]))
    monkeypatch.setattr(runtime, "get_executor", lambda: SimpleNamespace(checkpoint_store=store))
    result = await runtime.list_checkpoints("session", None, " tenant ", " user ")
    store.list_snapshots.assert_awaited_once_with("session", "tenant", "user")
    assert result["data"] == [{"run_id": "run", "stage": "done"}]


def test_admin_config_masks_search_secret_and_invalid_permission_defaults(monkeypatch):
    monkeypatch.setattr(settings.config.web_search, "bocha_api_key", "synthetic-long-secret")
    assert "synthetic-long-secret" not in json.dumps(runtime._safe_config_payload())
    monkeypatch.setattr(settings.config.permission, "default_mode", "invalid")
    assert runtime._default_permission_mode() == PermissionMode.DEFAULT


@pytest.mark.asyncio
async def test_trace_listing_without_filter_and_short_secret_mask(monkeypatch):
    from app.models.schemas import TraceEvent

    item = TraceEvent(trace_id="trace", event="test", timestamp="now")
    monkeypatch.setattr(runtime, "get_executor", lambda: SimpleNamespace(trace_recorder=SimpleNamespace(events=[item])))
    assert (await runtime.list_trace_events())["data"] == [item.model_dump()]
    assert runtime._mask_secret("short") == "****"
