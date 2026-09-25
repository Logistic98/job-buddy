import asyncio

import pytest

from app.core.tool.base import BaseTool, ToolExecutionContext
from app.models.schemas import ToolCall


class _SlowTool(BaseTool):
    name = "slow_test_tool"
    timeout_seconds = 0.01

    async def _run(self, arguments, context):
        await asyncio.sleep(1)
        return {"ok": True}


@pytest.mark.asyncio
async def test_safe_run_returns_readable_timeout_error():
    tool = _SlowTool()
    result = await tool.safe_run(
        ToolCall(id="slow-call", name=tool.name, arguments={}),
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert result.success is False
    assert result.error == "工具 slow_test_tool 执行超时（0.01 秒）"
