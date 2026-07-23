import pytest

from app.core.common.constants import PermissionMode
from app.core.tool.gateway import ToolGateway
from app.models.schemas import TaskUnderstandingResult, ToolCall


@pytest.mark.asyncio
async def test_gateway_includes_required_tool_from_capability_scope(fresh_registry):
    task = TaskUnderstandingResult(metadata={"capability_contract": {"required_tools": ["echo"]}})
    gateway = ToolGateway(fresh_registry)

    results = await gateway.search("no-match", task, limit=3)

    assert any(item.name == "echo" for item in results)


@pytest.mark.asyncio
async def test_gateway_blocks_tool_outside_capability_scope(fresh_registry, tool_context):
    task = TaskUnderstandingResult(metadata={"capability_contract": {"required_tools": ["echo"]}})
    gateway = ToolGateway(fresh_registry)

    result = await gateway.execute(
        ToolCall(id="toolu_test", name="glob", arguments={"pattern": "**/*"}),
        PermissionMode.DEFAULT,
        tool_context,
        task,
    )

    assert result.result.success is False
    assert result.permission_record is not None
    assert result.permission_record.allowed is False


@pytest.mark.asyncio
async def test_gateway_treats_present_empty_scope_as_no_tools(fresh_registry, tool_context):
    task = TaskUnderstandingResult(
        metadata={
            "capability_contract": {
                "tool_scope": "none",
                "required_tools": [],
                "allowed_tools": [],
            }
        }
    )
    gateway = ToolGateway(fresh_registry)

    assert await gateway.search("read files", task, limit=10) == []
    result = await gateway.execute(
        ToolCall(id="toolu_empty", name="glob", arguments={"pattern": "**/*"}),
        PermissionMode.DEFAULT,
        tool_context,
        task,
    )

    assert result.result.success is False
    assert result.result.metadata["policy"] == "capability_tool_scope"


@pytest.mark.asyncio
async def test_gateway_fails_closed_when_task_contract_is_missing(fresh_registry, tool_context):
    task = TaskUnderstandingResult(metadata={})
    gateway = ToolGateway(fresh_registry)

    assert await gateway.search("read files", task, limit=10) == []
    result = await gateway.execute(
        ToolCall(id="toolu_missing_contract", name="glob", arguments={"pattern": "**/*"}),
        PermissionMode.DEFAULT,
        tool_context,
        task,
    )

    assert result.result.success is False
    assert result.result.metadata["policy"] == "capability_tool_scope"


@pytest.mark.asyncio
async def test_gateway_requires_explicit_unrestricted_scope(fresh_registry, tool_context):
    task = TaskUnderstandingResult(metadata={"capability_contract": {"tool_scope": "unrestricted"}})
    gateway = ToolGateway(fresh_registry)

    result = await gateway.execute(
        ToolCall(id="toolu_unrestricted", name="glob", arguments={"pattern": "**/*"}),
        PermissionMode.DEFAULT,
        tool_context,
        task,
    )

    assert result.result.metadata.get("permission_denied") is not True
