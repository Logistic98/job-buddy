
import pytest

from app.core.agent.executor import AgentExecutor
from app.models.schemas import AgentRunRequest, ChatMessage
from app.core.common.constants import RuntimeStatus


@pytest.mark.asyncio
async def test_agent_runtime_echo():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="hello runtime")])
    response = await executor.execute(request)
    assert response.status in {RuntimeStatus.SUCCESS, RuntimeStatus.PAUSED}
    assert response.tool_results
