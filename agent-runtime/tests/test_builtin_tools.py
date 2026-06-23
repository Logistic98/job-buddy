
import pytest

from app.core.common.constants import PermissionMode
from app.core.tool.runtime import ToolRuntime
from app.models.schemas import ToolCall


@pytest.mark.asyncio
async def test_file_write_read_edit_pipeline(fresh_registry, tool_context, workspace):
    runtime = ToolRuntime(fresh_registry)
    target = workspace / "doc.txt"

    write_call = ToolCall(
        id="w1", name="file_write",
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
        id="e1", name="file_edit",
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
        id="e_d", name="file_edit",
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
        PermissionMode.DEFAULT, tool_context,
    )
    assert glob_result.success
    assert set(glob_result.output["files"]) == {"a.py", "b.py"}

    grep_result = await runtime.execute(
        ToolCall(id="g2", name="grep", arguments={"pattern": "hello", "glob": "*.py"}),
        PermissionMode.DEFAULT, tool_context,
    )
    assert grep_result.success
    assert grep_result.output["count"] >= 1
    assert any(m["path"] == "a.py" for m in grep_result.output["matches"])


@pytest.mark.asyncio
async def test_shell_allow_prefix_runs(fresh_registry, tool_context):
    runtime = ToolRuntime(fresh_registry)
    call = ToolCall(id="s1", name="shell_exec", arguments={"command": "pwd"})
    result = await runtime.execute(call, PermissionMode.AUTO, tool_context)
    assert result.success
    assert result.output["exit_code"] == 0


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
