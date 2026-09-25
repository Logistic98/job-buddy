from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import AsyncMock

import httpx
import pytest

from app.core.utils.time_utils import ExecutionTimer
from app.tools_builtin import shell_tool as shell
from app.tools_builtin.search_tools import GlobTool, GrepTool


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["timeout", "malformed", "shape", "forbidden"])
async def test_shell_transport_fails_closed_without_host_fallback(monkeypatch, tool_context, failure):
    client_type = httpx.AsyncClient
    requests = []

    def handle(request):
        requests.append(request)
        assert request.headers["X-Internal-Service-Token"] == "synthetic"
        if failure == "timeout":
            raise httpx.ReadTimeout("deadline")
        if failure == "malformed":
            return httpx.Response(200, text="not json")
        if failure == "shape":
            return httpx.Response(200, json=[])
        return httpx.Response(403)

    @asynccontextmanager
    async def client(**kwargs):
        assert kwargs["trust_env"] is False
        async with client_type(transport=httpx.MockTransport(handle)) as opened:
            yield opened

    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "synthetic")
    monkeypatch.setattr(shell.httpx, "AsyncClient", client)
    monkeypatch.setattr(shell.asyncio, "sleep", AsyncMock())
    host = AsyncMock()
    monkeypatch.setattr(shell.asyncio, "create_subprocess_shell", host)
    tool = shell.ShellTool()
    assert not (await tool.validate_input({}, tool_context)).result
    with pytest.raises(RuntimeError, match="agent-sandbox"):
        await tool._run_in_sandbox("pwd", Path(tool_context.workspace_dir))
    assert len(requests) == (2 if failure == "timeout" else 1)
    host.assert_not_awaited()
    assert tool._resolve_cwd("subdir", tool_context) == Path(tool_context.workspace_dir) / "subdir"


@pytest.mark.asyncio
async def test_search_limits_and_unreadable_files_do_not_leak_unbounded_results(tool_context, monkeypatch):
    root = Path(tool_context.workspace_dir)
    (root / "first.txt").write_text("match\nmatch", encoding="utf-8")
    (root / "second.txt").write_text("match", encoding="utf-8")
    assert (await GlobTool()._run({"pattern": "*.txt", "limit": 1}, tool_context))["count"] == 1
    grep = GrepTool()
    assert (await grep._run({"pattern": "match", "glob": "*.txt", "limit": 1}, tool_context))["count"] == 1
    original_read = Path.read_text

    def read(path, *args, **kwargs):
        if path.name == "first.txt":
            raise PermissionError("denied")
        return original_read(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", read)
    result = await grep._run({"pattern": "match", "glob": "*.txt"}, tool_context)
    assert [row["path"] for row in result["matches"]] == ["second.txt"]
    original_resolve = Path.resolve

    def resolve(path, *args, **kwargs):
        if path.name == "second.txt":
            raise OSError("disappeared")
        return original_resolve(path, *args, **kwargs)

    monkeypatch.setattr(Path, "resolve", resolve)
    assert (await grep._run({"pattern": "match", "glob": "*.txt"}, tool_context))["matches"] == []


def test_unstarted_timer_has_zero_latency():
    assert ExecutionTimer().get_latency_ms() == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [[], {}, {"data": {}}, {"data": {"error": {"message": "service failure"}}}])
async def test_boss_proxy_rejects_malformed_envelopes(body, monkeypatch, tool_context):
    from app.tools_builtin import boss_browser_tool as boss

    client_type = httpx.AsyncClient

    @asynccontextmanager
    async def client(**kwargs):
        async with client_type(transport=httpx.MockTransport(lambda request: httpx.Response(200, json=body))) as opened:
            yield opened

    monkeypatch.setattr(boss.httpx, "AsyncClient", client)
    tool_context.metadata.update(tenant_id="tenant", operator_id="user")
    tool = boss.BossBrowserTool()
    assert not (await tool.validate_input({}, tool_context)).result
    assert not (await tool.validate_input({"operation": "status", "payload": []}, tool_context)).result
    assert (await tool.validate_input({"operation": "status", "payload": None}, tool_context)).result
    with pytest.raises(RuntimeError):
        await tool._run({"operation": "status"}, tool_context)


def test_tool_output_truncates_and_handles_non_json_mapping_keys():
    from app.models.schemas import ToolCall, ToolResult
    from app.tools_builtin.echo_tool import EchoTool

    tool = EchoTool()
    call = ToolCall(id="call", name="echo")
    value = {(1, 2): "tuple key"}
    output, metadata = tool._normalize_output(call, value)
    assert output == value and metadata["truncated"] is False
    tool.max_result_size_chars = 5
    output, metadata = tool._normalize_output(call, "long value")
    assert len(output["preview"]) == 5
    assert metadata["truncated"] and metadata["storage"] == "not_persisted"
    result = ToolResult(
        tool_call_id="call",
        tool_name="echo",
        success=False,
        metadata={"warnings": ["warning", 2], "suggested_action": "retry"},
    )
    assert result.summary == "工具执行失败"
    assert result.warnings == ["warning", "2"] and result.next_actions == ["retry"]


def test_builtin_registration_is_idempotent_and_keeps_existing_tool():
    from app.core.tool.registry import ToolRegistry
    from app.tools_builtin import register_missing_builtin_tools

    registry = ToolRegistry()
    registered = register_missing_builtin_tools(registry)
    echo = registry.get("echo")
    assert "echo" in registered
    assert register_missing_builtin_tools(registry) == []
    assert registry.get("echo") is echo


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "arguments",
    [
        {},
        {"code": " ", "language": "python"},
        {"code": "x" * 200001, "language": "python"},
        {"code": "x\x00", "language": "python"},
        {"code": "pass", "language": "python", "args": ["x"] * 33},
        {"code": "pass", "language": "python", "args": [1]},
        {"code": "pass", "language": "python", "args": ["x" * 513]},
        {"code": "pass", "language": "python", "dependencies": ["x"] * 9},
        {"code": "pass", "language": "python", "dependencies": ["demo_pkg", "demo-pkg"]},
        {"code": "pass", "language": "python", "cwd": "/tmp"},
    ],
)
async def test_code_sandbox_rejects_invalid_or_policy_expanding_parameters(tool_context, arguments):
    from app.tools_builtin.sandbox_code_tool import SandboxCodeExecuteTool

    result = await SandboxCodeExecuteTool().validate_input(arguments, tool_context)
    assert not result.result
    assert result.error_code == 400


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["timeout", "malformed", "shape", "forbidden", "invalid_exit"])
async def test_code_sandbox_transport_fails_closed_without_retry(monkeypatch, tool_context, failure):
    from app.tools_builtin import sandbox_code_tool as code

    client_type = httpx.AsyncClient
    requests = []

    def handle(request):
        requests.append(request)
        assert request.url.path == "/v1/code-file"
        assert request.headers["X-Internal-Service-Token"] == "synthetic"
        if failure == "timeout":
            raise httpx.ReadTimeout("deadline")
        if failure == "malformed":
            return httpx.Response(200, text="not json")
        if failure == "shape":
            return httpx.Response(200, json=[])
        if failure == "invalid_exit":
            return httpx.Response(200, json={"returncode": True})
        return httpx.Response(403)

    @asynccontextmanager
    async def client(**kwargs):
        assert kwargs["trust_env"] is False
        async with client_type(transport=httpx.MockTransport(handle)) as opened:
            yield opened

    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "synthetic")
    monkeypatch.setattr(code.httpx, "AsyncClient", client)
    with pytest.raises(RuntimeError, match="agent-sandbox"):
        await code.SandboxCodeExecuteTool()._run({"language": "python", "code": "pass"}, tool_context)
    assert len(requests) == 1
