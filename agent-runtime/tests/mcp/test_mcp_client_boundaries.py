from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.core.common.settings import McpConfig, McpServerConfig
from app.core.tool.mcp_client import McpClient, McpProtocolError


@pytest.fixture
def remote(monkeypatch):
    client = McpClient(
        "test", McpServerConfig(url="https://mcp.invalid", headers={"Authorization": "synthetic"}), McpConfig()
    )
    session = AsyncMock()

    @asynccontextmanager
    async def opened():
        yield session

    monkeypatch.setattr(client, "_session", opened)
    return client, session


@pytest.mark.asyncio
async def test_list_and_call_normalize_protocol_data(remote):
    client, session = remote
    session.list_tools.return_value = SimpleNamespace(
        tools=[SimpleNamespace(name="tool", description=None, inputSchema={})]
    )
    assert await client.list_tools() == [
        {"name": "tool", "description": "", "input_schema": {"type": "object", "properties": {}}}
    ]
    session.call_tool.return_value = SimpleNamespace(isError=False, structuredContent={"answer": 1})
    assert (await client.call_tool("tool", {}))["structured"] == {"answer": 1}
    session.call_tool.assert_awaited_once_with("tool", arguments={})


@pytest.mark.asyncio
@pytest.mark.parametrize("limit", ["max_tools_per_server", "max_catalog_bytes"])
async def test_catalog_limits_reject_before_registration(remote, limit):
    client, session = remote
    setattr(client.limits, limit, 1)
    session.list_tools.return_value = SimpleNamespace(tools=[SimpleNamespace(name="one"), SimpleNamespace(name="two")])
    with pytest.raises(McpProtocolError, match="超过"):
        await client.list_tools()


@pytest.mark.parametrize(
    ("tool", "message"),
    [
        ({"name": ""}, "名称为空"),
        ({"name": "x" * 129}, "名称为空"),
        ({"name": "tool", "description": "x" * 4097}, "描述超过"),
    ],
)
def test_tool_metadata_limits(remote, tool, message):
    with pytest.raises(McpProtocolError, match=message):
        remote[0]._validate_tool_definition(tool)


@pytest.mark.parametrize("text", [" ", "plain", "{broken", '{"answer": 1}'])
def test_text_result_json_is_optional(remote, text):
    result = remote[0]._dump_call_result(SimpleNamespace(content=[SimpleNamespace(type="text", text=text)]))
    assert result["structured"] == ({"answer": 1} if text.startswith('{"answer"') else None)
    assert result["text"] == text
    assert remote[0]._try_parse_json(" ") is None


def test_raw_content_and_repr_fallback_are_bounded(remote):
    client, _ = remote
    structured = Mock(type="image")
    structured.model_dump.return_value = {"type": "image", "data": "x"}
    opaque = SimpleNamespace(type="unknown")
    result = client._dump_call_result(SimpleNamespace(content=[structured, opaque]))
    assert result["raw"][0] == {"type": "image", "data": "x"}
    assert result["raw"][1]["type"] == "unknown"
    assert "repr" in result["raw"][1]
    client.limits.max_result_bytes = 20
    with pytest.raises(McpProtocolError, match="超过"):
        client._dump_call_result(SimpleNamespace(content=[structured, structured]))
    client.limits.max_result_items = 1
    with pytest.raises(McpProtocolError, match="条目超过"):
        client._dump_call_result(SimpleNamespace(content=[structured, structured]))


@pytest.mark.asyncio
async def test_probe_reports_unreachable_server(remote):
    client, session = remote
    session.list_tools.return_value = SimpleNamespace(tools=[])
    assert await client.probe(1) is True
    session.list_tools.side_effect = ConnectionError("offline")
    assert await client.probe(1) is False


@pytest.mark.asyncio
@pytest.mark.parametrize("config", [McpServerConfig(transport="stdio"), McpServerConfig(url="")])
async def test_invalid_transport_configuration_is_rejected(config):
    client = McpClient("test", config, McpConfig())
    with pytest.raises(McpProtocolError):
        async with client._session():
            pytest.fail("invalid session opened")


@pytest.mark.asyncio
async def test_session_initializes_and_closes_sdk_streams(monkeypatch):
    import mcp
    import mcp.client.streamable_http

    session = AsyncMock()
    closed = []

    @asynccontextmanager
    async def stream(url, headers):
        assert url == "https://mcp.invalid"
        assert headers == {"Authorization": "synthetic"}
        yield "read", "write", None
        closed.append("stream")

    @asynccontextmanager
    async def sdk(read, write):
        assert (read, write) == ("read", "write")
        yield session
        closed.append("session")

    monkeypatch.setattr(mcp.client.streamable_http, "streamablehttp_client", stream)
    monkeypatch.setattr(mcp, "ClientSession", sdk)
    client = McpClient(
        "test", McpServerConfig(url="https://mcp.invalid", headers={"Authorization": "synthetic"}), McpConfig()
    )
    async with client._session() as current:
        assert current is session
        session.initialize.assert_awaited_once()
    assert closed == ["session", "stream"]


@pytest.mark.asyncio
async def test_missing_sdk_is_actionable(monkeypatch):
    import sys

    monkeypatch.setitem(sys.modules, "mcp", None)
    client = McpClient("test", McpServerConfig(url="https://mcp.invalid"), McpConfig())
    with pytest.raises(McpProtocolError, match="未安装"):
        async with client._session():
            pytest.fail("missing SDK opened")
