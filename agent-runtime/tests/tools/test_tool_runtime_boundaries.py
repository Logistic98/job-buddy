from unittest.mock import AsyncMock

import pytest

from app.core.agent.loop_controller import LoopController
from app.core.capability.models import ProfileDefinition
from app.core.common.constants import PermissionMode, ToolRiskLevel
from app.core.common.settings import settings
from app.core.tool.permission import PermissionService
from app.core.tool.registry import ToolRegistry
from app.core.tool.runtime import ToolRuntime
from app.core.tool.search import ToolSearchService
from app.models.schemas import TaskUnderstandingResult, ToolCall, ToolResult
from app.tools_builtin.echo_tool import EchoTool
from app.tools_builtin.file_edit_tool import FileEditTool
from app.tools_builtin.file_read_tool import FileReadTool
from app.tools_builtin.file_write_tool import FileWriteTool


@pytest.mark.asyncio
async def test_empty_registry_search_and_disabled_runtime_do_not_execute(tool_context, monkeypatch):
    registry = ToolRegistry()
    assert await ToolSearchService(registry).search("echo") == []
    registry.unregister("missing")
    monkeypatch.setattr(settings.config.tool_runtime, "enabled", False)
    result = await ToolRuntime(registry).execute(ToolCall(id="call", name="echo"), PermissionMode.DEFAULT, tool_context)
    assert not result.success
    assert "关闭" in result.error


@pytest.mark.asyncio
async def test_retry_uses_bounded_backoff_and_preserves_call_identity(tool_context, monkeypatch):
    registry = ToolRegistry()
    tool = EchoTool()
    tool.max_retries = 1
    tool.safe_run = AsyncMock(
        side_effect=[
            ToolResult(tool_call_id="call", tool_name="echo", success=False, error="temporary"),
            ToolResult(tool_call_id="call", tool_name="echo", success=True, output="done"),
        ]
    )
    registry.register(tool)
    sleep = AsyncMock()
    monkeypatch.setattr("app.core.tool.runtime.asyncio.sleep", sleep)
    result = await ToolRuntime(registry).execute(ToolCall(id="call", name="echo"), PermissionMode.DEFAULT, tool_context)
    assert result.success and result.output == "done"
    assert result.metadata["attempt"] == 2
    assert tool.safe_run.await_count == 2
    sleep.assert_awaited_once_with(settings.config.tool_runtime.retry_backoff_seconds)


@pytest.mark.asyncio
async def test_high_risk_default_mode_requires_confirmation():
    definition = EchoTool().definition()
    definition.risk_level = ToolRiskLevel.HIGH
    decision = await PermissionService().check(definition, ToolCall(id="call", name="echo"), PermissionMode.DEFAULT)
    assert not decision.allowed
    assert decision.requires_confirmation


@pytest.mark.asyncio
@pytest.mark.parametrize("tool_class", [FileReadTool, FileWriteTool, FileEditTool])
async def test_file_tools_reject_missing_parameters(tool_class, tool_context):
    result = await tool_class().validate_input({}, tool_context)
    assert not result.result
    assert result.error_code == 400


@pytest.mark.asyncio
async def test_file_read_blocks_parent_traversal_and_edit_requires_existing_file(tool_context):
    result = await FileReadTool().validate_input({"path": "../outside"}, tool_context)
    assert not result.result and result.error_code == 403
    result = await FileEditTool().validate_input({"path": "missing", "old_text": "a", "new_text": "b"}, tool_context)
    assert not result.result and result.error_code == 404


def test_loop_routing_respects_missing_task_entrypoint_and_turn_limit():
    controller = LoopController()
    profile = ProfileDefinition(id="test", name="Test")
    assert controller.route_after_task_understanding({}, profile) == "collect_context"
    state = {
        "task_understanding": TaskUnderstandingResult(original_query="question"),
        "directive": {"next_action": "run_runtime_planner"},
    }
    assert controller.route_after_task_understanding(state, profile) == "finalize"
    state = {"turn_count": 2, "budget": {"max_turns": 2}, "reflection": {"decision": "retry"}}
    assert controller.route_after_reflect(state) == "finalize"
    assert state["stop_reason"] == "max_turns"


@pytest.mark.asyncio
async def test_gateway_reports_missing_tools_and_reviewer_outage(tool_context, fresh_registry, monkeypatch):
    from types import SimpleNamespace

    from app.core.tool.gateway import ToolGateway

    gateway = ToolGateway(
        fresh_registry, transcript_reviewer=SimpleNamespace(review=AsyncMock(side_effect=RuntimeError("offline")))
    )
    missing = await gateway.execute(ToolCall(id="missing", name="absent"), PermissionMode.DEFAULT, tool_context)
    assert not missing.result.success and "不存在" in missing.result.error
    monkeypatch.setattr(settings.config.transcript_review, "enabled", True)
    result = await gateway.execute(
        ToolCall(id="write", name="file_write", arguments={"path": "file.txt", "content": "text"}),
        PermissionMode.AUTO,
        tool_context,
    )
    assert not result.result.success
    assert "失败关闭" in result.result.error
    assert result.permission_record.allowed is False
    from pathlib import Path

    assert not (Path(tool_context.workspace_dir) / "file.txt").exists()


@pytest.mark.asyncio
async def test_gateway_search_handles_unrestricted_invalid_and_missing_required_tool(fresh_registry):
    from app.core.tool.gateway import ToolGateway

    gateway = ToolGateway(fresh_registry)
    unrestricted = TaskUnderstandingResult(
        original_query="echo", metadata={"capability_contract": {"tool_scope": "unrestricted"}}
    )
    assert await gateway.search("echo", unrestricted, 1)
    invalid = TaskUnderstandingResult(
        original_query="echo", metadata={"capability_contract": {"tool_scope": "invalid"}}
    )
    assert await gateway.search("echo", invalid, 1) == []
    missing = TaskUnderstandingResult(
        original_query="echo",
        metadata={"capability_contract": {"tool_scope": "allowlist", "required_tools": ["absent"]}},
    )
    assert await gateway.search("echo", missing, 1) == []


def test_registry_rejects_empty_names_and_alias_collisions():
    registry = ToolRegistry()
    empty = EchoTool()
    empty.name = ""
    with pytest.raises(ValueError, match="名称不能为空"):
        registry.register(empty)
    first = EchoTool()
    first.aliases = ["shared"]
    registry.register(first, source="original")
    second = EchoTool()
    second.name = "other"
    second.aliases = ["shared"]
    with pytest.raises(ValueError, match="别名冲突"):
        registry.register(second)
    assert registry.get("shared") is first
    with pytest.raises(ValueError, match="空名称或重复名称"):
        registry.replace_source("original", [empty])
    replacement = EchoTool()
    replacement.name = "replacement"
    replacement.aliases = ["shared"]
    with pytest.raises(ValueError, match="别名冲突"):
        registry.replace_source("new", [replacement])
    assert registry.get("shared") is first


def test_gateway_keeps_required_tools_and_normalizes_trace_metadata():
    from app.core.tool.gateway import ToolGateway

    registry = ToolRegistry()
    registry.register(EchoTool())
    gateway = ToolGateway(registry)
    task = TaskUnderstandingResult(
        metadata={"capability_contract": {"required_tools": ["echo"], "tool_scope": "allowlist"}}
    )
    assert [item.name for item in gateway._include_required_tools(task, [], 0)] == ["echo"]
    assert gateway._task_tool_policy(None)[0] == "unrestricted"
    result = ToolResult(tool_call_id="call", tool_name="echo", success=True, metadata={"trace_id": "trace"})
    assert gateway._normalize_result(result).trace_id == "trace"


def test_disabled_tool_budget_cannot_enter_planner(monkeypatch):
    monkeypatch.setattr(settings.config.runtime, "max_tool_calls", 0)
    result = LoopController().route_after_task_understanding(
        {"task_understanding": TaskUnderstandingResult(), "directive": {"next_action": "run_runtime_planner"}},
        ProfileDefinition(id="test", name="Test"),
    )
    assert result == "finalize"


def test_gateway_fails_closed_for_mutated_task_metadata_and_normalizes_legacy_null_actions():
    from app.core.tool.gateway import ToolGateway

    gateway = ToolGateway(ToolRegistry())
    task = TaskUnderstandingResult()
    task.metadata = None
    assert gateway._task_tool_policy(task) == ("none", set(), [])
    result = ToolResult(tool_call_id="call", tool_name="echo", success=True)
    result.next_actions = None
    assert gateway._normalize_result(result).next_actions == []
