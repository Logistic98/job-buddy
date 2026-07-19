import httpx
import pytest

import app.tools_builtin.shell_tool as shell_tool_module
from app.core.common.constants import PermissionMode
from app.core.tool.runtime import ToolRuntime
from app.models.schemas import ToolCall


@pytest.mark.asyncio
async def test_file_write_read_edit_pipeline(fresh_registry, tool_context, workspace):
    runtime = ToolRuntime(fresh_registry)
    target = workspace / "doc.txt"

    write_call = ToolCall(
        id="w1",
        name="file_write",
        arguments={"path": str(target), "content": "hello world\nsecond line"},
    )
    write_result = await runtime.execute(write_call, PermissionMode.AUTO, tool_context)
    assert write_result.success
    assert target.exists()

    read_call = ToolCall(id="r1", name="file_read", arguments={"path": str(target)})
    read_result = await runtime.execute(read_call, PermissionMode.DEFAULT, tool_context)
    assert read_result.success
    assert "hello world" in read_result.output["content"]
    assert read_result.output["total_lines"] == 2

    edit_call = ToolCall(
        id="e1",
        name="file_edit",
        arguments={"path": str(target), "old_text": "hello world", "new_text": "hi world"},
    )
    edit_result = await runtime.execute(edit_call, PermissionMode.AUTO, tool_context)
    assert edit_result.success
    assert "hi world" in target.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_file_write_rejects_path_outside_workspace(fresh_registry, tool_context, tmp_path):
    runtime = ToolRuntime(fresh_registry)
    outside = tmp_path.parent / "escape.txt"
    call = ToolCall(id="w_bad", name="file_write", arguments={"path": str(outside), "content": "x"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert not result.success
    assert "工作区" in (result.error or "")


@pytest.mark.asyncio
async def test_file_edit_rejects_same_prefix_sibling_workspace(fresh_registry, tool_context, workspace):
    runtime = ToolRuntime(fresh_registry)
    sibling = workspace.parent / f"{workspace.name}-outside"
    sibling.mkdir()
    target = sibling / "secret.txt"
    target.write_text("secret", encoding="utf-8")
    call = ToolCall(
        id="e_escape",
        name="file_edit",
        arguments={"path": str(target), "old_text": "secret", "new_text": "changed"},
    )
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert not result.success
    assert "工作区" in (result.error or "")
    assert target.read_text(encoding="utf-8") == "secret"


@pytest.mark.asyncio
async def test_grep_skips_symlink_to_file_outside_workspace(fresh_registry, tool_context, workspace, tmp_path):
    outside = tmp_path.parent / f"{tmp_path.name}-external.txt"
    outside.write_text("credential-marker", encoding="utf-8")
    (workspace / "external-link.txt").symlink_to(outside)
    runtime = ToolRuntime(fresh_registry)
    result = await runtime.execute(
        ToolCall(id="g_escape", name="grep", arguments={"pattern": "credential-marker"}),
        PermissionMode.DEFAULT,
        tool_context,
    )
    assert result.success
    assert result.output["matches"] == []


@pytest.mark.asyncio
async def test_file_write_requires_overwrite_flag(fresh_registry, tool_context, workspace):
    runtime = ToolRuntime(fresh_registry)
    target = workspace / "exist.txt"
    target.write_text("old", encoding="utf-8")
    call = ToolCall(id="w_d", name="file_write", arguments={"path": str(target), "content": "new"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert not result.success


@pytest.mark.asyncio
async def test_file_edit_requires_unique_match(fresh_registry, tool_context, workspace):
    runtime = ToolRuntime(fresh_registry)
    target = workspace / "dup.txt"
    target.write_text("aa aa aa", encoding="utf-8")
    call = ToolCall(
        id="e_d",
        name="file_edit",
        arguments={"path": str(target), "old_text": "aa", "new_text": "bb"},
    )
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert not result.success


@pytest.mark.asyncio
async def test_glob_and_grep(fresh_registry, tool_context, workspace):
    (workspace / "a.py").write_text("import os\nprint('hello')\n", encoding="utf-8")
    (workspace / "b.py").write_text("def foo():\n    return 'world'\n", encoding="utf-8")
    (workspace / "note.md").write_text("note hello", encoding="utf-8")

    runtime = ToolRuntime(fresh_registry)

    glob_result = await runtime.execute(
        ToolCall(id="g1", name="glob", arguments={"pattern": "*.py"}),
        PermissionMode.DEFAULT,
        tool_context,
    )
    assert glob_result.success
    assert set(glob_result.output["files"]) == {"a.py", "b.py"}

    grep_result = await runtime.execute(
        ToolCall(id="g2", name="grep", arguments={"pattern": "hello", "glob": "*.py"}),
        PermissionMode.DEFAULT,
        tool_context,
    )
    assert grep_result.success
    assert grep_result.output["count"] >= 1
    assert any(m["path"] == "a.py" for m in grep_result.output["matches"])


class _FakeSandboxResponse:
    def __init__(self, status_code=200, body=None):
        self.status_code = status_code
        self._body = body or {"ok": True, "returncode": 0, "stdout": "/tmp\n", "stderr": "", "args": []}

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("error", request=None, response=None)

    def json(self):
        return self._body


class _FakeSandboxClient:
    last_payload = None
    fail_with = None

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def post(self, url, json=None, headers=None):
        if _FakeSandboxClient.fail_with is not None:
            raise _FakeSandboxClient.fail_with
        _FakeSandboxClient.last_payload = {"url": url, "json": json}
        return _FakeSandboxResponse()


@pytest.mark.asyncio
async def test_shell_allow_prefix_runs_in_sandbox(fresh_registry, tool_context, monkeypatch):
    _FakeSandboxClient.fail_with = None
    _FakeSandboxClient.last_payload = None
    monkeypatch.setattr(shell_tool_module.httpx, "AsyncClient", _FakeSandboxClient)
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="s1", name="shell_exec", arguments={"command": "pwd"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert result.success
    assert result.output["exit_code"] == 0
    assert result.output["sandboxed"] is True
    payload = _FakeSandboxClient.last_payload
    assert payload["url"].endswith("/v1/shell")
    assert payload["json"]["command"].startswith("cd ")
    assert payload["json"]["command"].endswith("&& pwd")
    assert payload["json"]["policy"]["filesystem"]["allowWrite"] == []
    assert payload["json"]["policy"]["network"]["allowedDomains"] == []


@pytest.mark.asyncio
async def test_shell_sandbox_unavailable_fails_without_host_fallback(fresh_registry, tool_context, monkeypatch):
    _FakeSandboxClient.fail_with = httpx.ConnectError("connection refused")
    monkeypatch.setattr(shell_tool_module.httpx, "AsyncClient", _FakeSandboxClient)
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="s1b", name="shell_exec", arguments={"command": "pwd"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    _FakeSandboxClient.fail_with = None
    assert not result.success
    assert "agent-sandbox" in (result.error or "")
    assert "回退" in (result.error or "")


@pytest.mark.asyncio
async def test_shell_host_path_only_when_sandbox_disabled(fresh_registry, tool_context, monkeypatch):
    from app.core.common.settings import settings as runtime_settings

    monkeypatch.setattr(runtime_settings.config.tool_runtime, "shell_sandbox_enabled", False)
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="s1c", name="shell_exec", arguments={"command": "pwd"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert result.success
    assert result.output["exit_code"] == 0
    assert result.output["sandboxed"] is False


@pytest.mark.asyncio
async def test_shell_deny_pattern_blocks(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="s2", name="shell_exec", arguments={"command": "rm -rf /tmp/nope"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert not result.success
    assert "禁止规则" in (result.error or "")


@pytest.mark.asyncio
async def test_shell_unlisted_prefix_blocked(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="s3", name="shell_exec", arguments={"command": "echo hi"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert not result.success
    assert "shell_allow_prefixes" in (result.error or "")
