
import pytest

from app.core.common.constants import PermissionMode
from app.core.common.settings import settings
from app.core.tool.permission import PermissionService
from app.models.schemas import ToolCall
from app.tools_builtin.echo_tool import EchoTool
from app.tools_builtin.file_write_tool import FileWriteTool


def _call(name: str) -> ToolCall:
    return ToolCall(id="call_test", name=name, arguments={})


@pytest.mark.asyncio
async def test_low_risk_tool_allowed_in_default_mode():
    decision = await PermissionService().check(EchoTool().definition(), _call("echo"), PermissionMode.DEFAULT)
    assert decision.allowed


@pytest.mark.asyncio
async def test_destructive_tool_blocked_in_default_mode():
    decision = await PermissionService().check(FileWriteTool().definition(), _call("file_write"), PermissionMode.DEFAULT)
    assert not decision.allowed
    assert decision.requires_confirmation


@pytest.mark.asyncio
async def test_destructive_tool_allowed_in_auto_mode():
    decision = await PermissionService().check(FileWriteTool().definition(), _call("file_write"), PermissionMode.AUTO)
    assert decision.allowed


@pytest.mark.asyncio
async def test_plan_mode_blocks_all_tools():
    decision = await PermissionService().check(EchoTool().definition(), _call("echo"), PermissionMode.PLAN)
    assert not decision.allowed


@pytest.mark.asyncio
async def test_bypass_mode_allows_everything():
    decision = await PermissionService().check(FileWriteTool().definition(), _call("file_write"), PermissionMode.BYPASS)
    assert decision.allowed


@pytest.mark.asyncio
async def test_deny_list_overrides_allow(monkeypatch):
    monkeypatch.setattr(settings.config.permission, "deny_tools", ["echo"])
    try:
        decision = await PermissionService().check(EchoTool().definition(), _call("echo"), PermissionMode.AUTO)
        assert not decision.allowed
        assert "deny" in decision.reason
    finally:
        monkeypatch.setattr(settings.config.permission, "deny_tools", [])


@pytest.mark.asyncio
async def test_allow_list_excludes_unlisted(monkeypatch):
    monkeypatch.setattr(settings.config.permission, "allow_tools", ["file_read"])
    try:
        decision = await PermissionService().check(EchoTool().definition(), _call("echo"), PermissionMode.DEFAULT)
        assert not decision.allowed
    finally:
        monkeypatch.setattr(settings.config.permission, "allow_tools", [])
