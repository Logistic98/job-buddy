from types import SimpleNamespace

import pytest

from app.core.common.settings import McpConfig, McpServerConfig
from app.core.tool.mcp_client import McpClient, McpProtocolError


def client(**overrides):
    limits = McpConfig(**overrides)
    return McpClient(
        "test",
        McpServerConfig(enabled=True, url="http://example.test/mcp"),
        limits,
    )


def test_mcp_result_rejects_text_before_joining_unbounded_content():
    value = SimpleNamespace(
        isError=False,
        structuredContent=None,
        content=[
            SimpleNamespace(type="text", text="a" * 40),
            SimpleNamespace(type="text", text="b" * 40),
        ],
    )

    with pytest.raises(McpProtocolError, match="超过 64 字节"):
        client(max_result_bytes=64)._dump_call_result(value)


def test_mcp_result_rejects_excessive_structure_depth():
    nested = {}
    cursor = nested
    for _ in range(8):
        cursor["child"] = {}
        cursor = cursor["child"]
    value = SimpleNamespace(isError=False, structuredContent=nested, content=[])

    with pytest.raises(McpProtocolError, match="嵌套深度"):
        client(max_result_depth=4)._dump_call_result(value)


def test_mcp_catalog_rejects_oversized_schema_before_registration():
    tool = {
        "name": "large",
        "description": "test",
        "input_schema": {"type": "object", "description": "x" * 100},
    }

    with pytest.raises(McpProtocolError, match="内容超过 64 字节"):
        client(max_schema_bytes=64)._validate_tool_definition(tool)
