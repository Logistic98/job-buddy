
import pytest

from app.core.common.constants import PermissionMode
from app.core.tool.runtime import ToolRuntime
from app.models.schemas import ToolCall


@pytest.mark.asyncio
async def test_runtime_executes_echo_in_default(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="call_1", name="echo", arguments={"text": "hello"})
    result = await runtime.execute(call, PermissionMode.DEFAULT, tool_context)
    assert result.success
    assert result.output == {"text": "hello"}
    assert runtime.last_permission_record.allowed


@pytest.mark.asyncio
async def test_runtime_rejects_unknown_tool(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="call_x", name="does_not_exist", arguments={})
    result = await runtime.execute(call, PermissionMode.DEFAULT, tool_context)
    assert not result.success
    assert "工具不存在" in result.error


@pytest.mark.asyncio
async def test_runtime_blocks_destructive_in_default(fresh_registry, tool_context, workspace):
    runtime = ToolRuntime(fresh_registry)
    target = workspace / "out.txt"
    call = ToolCall(id="call_w", name="file_write", arguments={"path": str(target), "content": "abc"})
    result = await runtime.execute(call, PermissionMode.DEFAULT, tool_context)
    assert not result.success
    assert result.metadata.get("permission_denied")


@pytest.mark.asyncio
async def test_runtime_alias_resolution(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="call_a", name="print_text", arguments={"text": "hi"})
    result = await runtime.execute(call, PermissionMode.DEFAULT, tool_context)
    assert result.success
    assert result.tool_name == "echo"


@pytest.mark.asyncio
async def test_runtime_validation_failure_reports_error(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="call_e", name="echo", arguments={})
    result = await runtime.execute(call, PermissionMode.DEFAULT, tool_context)
    assert not result.success
    assert "text" in (result.error or "")
