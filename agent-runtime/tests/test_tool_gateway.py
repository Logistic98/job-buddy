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
