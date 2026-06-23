
import pytest

from app.core.agent.executor import AgentExecutor
from app.core.common.constants import RuntimeStatus
from app.models.schemas import AgentRunRequest, ChatMessage


@pytest.mark.asyncio
async def test_executor_end_to_end_echo_runs_to_completion():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="请回显 hello runtime")])
    response = await executor.execute(request)

    assert response.status in {RuntimeStatus.SUCCESS, RuntimeStatus.PAUSED}
    assert response.run_id and response.trace_id and response.session_id
    assert response.tool_results
    echo_result = next((r for r in response.tool_results if r.tool_name == "echo"), None)
    assert echo_result is not None
    assert echo_result.success


@pytest.mark.asyncio
async def test_executor_emits_trace_events():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="请回显 hi")])
    response = await executor.execute(request)
    events = {e.event for e in response.trace_events}
    assert "run_start" in events
    assert "run_end" in events


@pytest.mark.asyncio
async def test_executor_records_permission_decision():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="请回显 hello")])
    response = await executor.execute(request)
    if response.permission_records:
        assert all(r.allowed for r in response.permission_records if r.tool_name == "echo")
