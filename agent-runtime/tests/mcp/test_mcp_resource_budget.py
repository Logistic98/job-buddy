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


@pytest.mark.parametrize("kind", ["list", "dict"])
def test_cyclic_resources_fail_before_serialization(kind):
    from app.core.tool.resource_budget import ResourceBudget, ResourceBudgetExceeded, enforce_resource_budget

    value = [] if kind == "list" else {}
    if kind == "list":
        value.append(value)
    else:
        value["self"] = value
    with pytest.raises(ResourceBudgetExceeded, match="循环引用"):
        enforce_resource_budget(value, ResourceBudget(1000, 100, 10), "resource")


def test_resource_node_budget_and_non_json_scalars():
    from app.core.tool.resource_budget import ResourceBudget, ResourceBudgetExceeded, enforce_resource_budget

    with pytest.raises(ResourceBudgetExceeded, match="节点数量"):
        enforce_resource_budget([1, 2], ResourceBudget(1000, 2, 10), "resource")

    class TextValue:
        def __str__(self):
            return "中文"

    assert enforce_resource_budget(TextValue(), ResourceBudget(6, 2, 1), "resource") == 6
    assert enforce_resource_budget((None, True, 1, 1.5), ResourceBudget(100, 10, 2), "resource") == 12
