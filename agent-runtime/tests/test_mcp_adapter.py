"""MCP 适配层单元测试。

不依赖真实 MCP 服务,通过 mock McpClient 验证:
1. 启动期能把多个 MCP 工具按 name_prefix 注册到 ToolRegistry。
2. 调用期能把 MCP 返回结果归一化为 ToolResult。
3. 单个服务连接失败不影响其他服务和整体启动。
"""

from uuid import uuid4

import pytest

from app.core.common.settings import McpConfig, McpServerConfig
from app.core.tool.base import ToolExecutionContext
from app.core.tool.mcp_adapter import McpToolAdapter, register_mcp_tools
from app.core.tool.registry import ToolRegistry
from app.models.schemas import ToolCall


class _StubClient:
    def __init__(self, server_id, tools=None, call_results=None, list_raises=None):
        self.server_id = server_id
        self.config = type("Cfg", (), {"transport": "streamable_http"})()
        self._tools = tools or []
        self._call_results = call_results or {}
        self._list_raises = list_raises
        self.calls = []

    async def list_tools(self):
        if self._list_raises:
            raise self._list_raises
        return self._tools

    async def call_tool(self, name, arguments):
        self.calls.append((name, arguments))
        return self._call_results.get(name, {"is_error": False, "structured": {"echo": arguments}, "text": None})


def _tool_context():
    return ToolExecutionContext(
        run_id=f"run_{uuid4().hex[:8]}",
        trace_id=f"trace_{uuid4().hex[:8]}",
        session_id=f"session_{uuid4().hex[:8]}",
    )


@pytest.mark.asyncio
async def test_register_mcp_tools_applies_prefix(monkeypatch):
    registry = ToolRegistry()

    stub = _StubClient(
        server_id="bosszp",
        tools=[
            {"name": "get_recommend_jobs_tool", "description": "拉推荐岗位", "input_schema": {"type": "object"}},
            {"name": "login_full_auto", "description": "自动登录", "input_schema": {"type": "object"}},
        ],
    )

    from app.core.tool import mcp_adapter

    monkeypatch.setattr(mcp_adapter, "McpClient", lambda server_id, config: stub)

    config = McpConfig(
        enabled=True,
        connect_timeout_seconds=5,
        servers={
            "bosszp": McpServerConfig(
                enabled=True,
                transport="streamable_http",
                url="http://127.0.0.1:8000/mcp",
                name_prefix="boss_",
                tool_tag="job",
            )
        },
    )

    registered = await register_mcp_tools(registry, config)

    assert registered == ["boss_get_recommend_jobs_tool", "boss_login_full_auto"]
    assert registry.has("boss_get_recommend_jobs_tool")
    assert registry.get("boss_login_full_auto").description == "自动登录"
    assert "job" in registry.get("boss_login_full_auto").tags


@pytest.mark.asyncio
async def test_register_mcp_tools_skip_disabled_server(monkeypatch):
    registry = ToolRegistry()

    from app.core.tool import mcp_adapter

    monkeypatch.setattr(mcp_adapter, "McpClient", lambda server_id, config: pytest.fail("should not be called"))

    config = McpConfig(
        enabled=True,
        servers={"bosszp": McpServerConfig(enabled=False, url="http://x/mcp")},
    )

    registered = await register_mcp_tools(registry, config)
    assert registered == []


@pytest.mark.asyncio
async def test_register_mcp_tools_tolerates_unreachable_server(monkeypatch):
    registry = ToolRegistry()

    stub = _StubClient(server_id="bosszp", list_raises=ConnectionError("connection refused"))

    from app.core.tool import mcp_adapter

    monkeypatch.setattr(mcp_adapter, "McpClient", lambda server_id, config: stub)

    config = McpConfig(
        enabled=True,
        connect_timeout_seconds=1,
        servers={"bosszp": McpServerConfig(enabled=True, url="http://127.0.0.1:8000/mcp", name_prefix="boss_")},
    )

    registered = await register_mcp_tools(registry, config)
    assert registered == []


@pytest.mark.asyncio
async def test_adapter_run_returns_structured_payload():
    stub = _StubClient(
        server_id="bosszp",
        call_results={
            "get_recommend_jobs_tool": {
                "is_error": False,
                "structured": {"jobs": [{"jobName": "Java 大模型应用开发", "salaryDesc": "40-50K"}]},
                "text": None,
            }
        },
    )
    adapter = McpToolAdapter(
        client=stub,
        remote_tool_name="get_recommend_jobs_tool",
        display_name="boss_get_recommend_jobs_tool",
        description="推荐岗位",
        input_schema={"type": "object", "properties": {"page": {"type": "integer"}}},
    )

    call = ToolCall(id="call_1", name="boss_get_recommend_jobs_tool", arguments={"page": 1})
    result = await adapter.safe_run(call, _tool_context())

    assert result.success is True
    assert result.output == {"jobs": [{"jobName": "Java 大模型应用开发", "salaryDesc": "40-50K"}]}
    assert stub.calls == [("get_recommend_jobs_tool", {"page": 1})]


@pytest.mark.asyncio
async def test_adapter_propagates_is_error():
    stub = _StubClient(
        server_id="bosszp",
        call_results={
            "send_greeting_tool": {
                "is_error": True,
                "structured": {"status": "error", "message": "cookie 已失效"},
                "text": None,
            }
        },
    )
    adapter = McpToolAdapter(
        client=stub,
        remote_tool_name="send_greeting_tool",
        display_name="boss_send_greeting_tool",
        description="打招呼",
        input_schema={"type": "object"},
    )

    call = ToolCall(id="call_2", name="boss_send_greeting_tool", arguments={})
    result = await adapter.safe_run(call, _tool_context())

    assert result.success is False
    assert "cookie 已失效" in result.error
